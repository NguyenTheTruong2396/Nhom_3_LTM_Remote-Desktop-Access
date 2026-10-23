package com.remote.server.network;

import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketParser.Packet;
import com.remote.common.network.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.remote.common.network.NetworkConstants;

import com.remote.server.nativeapi.JniBridge;
import com.remote.server.nativeapi.NativeCapture;

import com.remote.common.model.InputEventPacket;
import com.remote.server.control.RobotEventReplayer;

/**
 * Handles one incoming TCP connection using the TLV custom protocol.
 *
 * Week 2 scope:
 *   1. Read AUTH_REQ packet (TLV).
 *   2. Validate sessionId + passcode against SessionManager.
 *   3. Reply AUTH_RESP (success/failure).
 *   4. On success: invoke post-auth hook (screen frame send will be wired here in Week 2 Step 6).
 *   5. Loop: continue reading packets until DISCONNECT or EOF.
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final String clientAddress;
    private final SessionManager sessionManager;
    private final RobotEventReplayer robotReplayer;
    private VideoStreamSender videoStreamSender;


    private java.io.FileOutputStream fileOutputStream;
    private String receivingFileName;
    private long receivingFileSize;
    private long receivedBytes;

    public ClientHandler(Socket socket, SessionManager sessionManager) {
        this.clientSocket = socket;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
        this.sessionManager = sessionManager;
        this.robotReplayer = new RobotEventReplayer();
    }

    @Override
    public void run() {
        System.out.println("[ClientHandler] Client connected from: " + clientAddress);

        try (
            DataInputStream  dis = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())
        ) {
            // --- AUTH handshake (TLV) ---
            boolean authenticated = handleAuth(dis, dos);
            if (!authenticated) {
                System.out.println("[ClientHandler] Auth failed, closing connection: " + clientAddress);
                return;
            }

            // --- Post-auth session loop ---
            onAuthenticated(dis, dos);

        } catch (EOFException | SocketException e) {
            System.out.println("[ClientHandler] Client disconnected: " + clientAddress);
        } catch (IOException e) {
            System.err.println("[ClientHandler] IO Error with client " + clientAddress + ": " + e.getMessage());
        } finally {
            closeSocket();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the AUTH_REQ packet, verifies credentials, and sends AUTH_RESP.
     *
     * @return {@code true} if authentication succeeded
     */
    private boolean handleAuth(DataInputStream dis, DataOutputStream dos) throws IOException {
        Packet packet = PacketParser.read(dis);

        if (packet.type() != PacketType.AUTH_REQ) {
            System.err.println("[ClientHandler] Expected AUTH_REQ (0x01) but got: " + packet);
            PacketBuilder.writeAuthResponse(dos, false);
            return false;
        }

        // Payload format: "sessionId|passcode"
        String payload   = packet.payloadAsString();
        String[] parts   = payload.split("\\|", 2);
        String sessionId = parts.length > 0 ? parts[0] : "";
        String passcode  = parts.length > 1 ? parts[1] : "";

        System.out.println("[ClientHandler] AUTH_REQ received — sessionId=" + sessionId
                + " from " + clientAddress);

        boolean ok = sessionManager.authenticate(sessionId, passcode);
        PacketBuilder.writeAuthResponse(dos, ok);

        if (ok) {
            System.out.println("[ClientHandler] Auth SUCCESS for sessionId=" + sessionId);
        } else {
            System.out.println("[ClientHandler] Auth FAILED  for sessionId=" + sessionId);
        }
        return ok;
    }

    /**
     * Called after successful authentication.
     * Starts continuous video stream and processes incoming packets (including input events).
     */
     private void onAuthenticated(DataInputStream dis, DataOutputStream dos) throws IOException {
        System.out.println("[ClientHandler] Session established, starting video stream: " + clientAddress);

        // Khởi động luồng phát video liên tục 30 FPS tới Client
        this.videoStreamSender = new VideoStreamSender(clientSocket, dos);
        this.videoStreamSender.start();

        // Cài đặt timeout giám sát Heartbeat (10 giây không có packet -> ngắt kết nối)
        clientSocket.setSoTimeout(NetworkConstants.HEARTBEAT_TIMEOUT_MS);

        while (!clientSocket.isClosed()) {
            try {
                Packet packet = PacketParser.read(dis);
                System.out.println("[ClientHandler] Received " + packet + " from " + clientAddress);

                switch (packet.type()) {
                    case PacketType.PING:
                        synchronized (dos) {
                            PacketBuilder.writeEmpty(dos, PacketType.PONG);
                        }
                        System.out.println("[ClientHandler] Sent PONG to " + clientAddress);
                        break;

                    case PacketType.DISCONNECT:
                        System.out.println("[ClientHandler] DISCONNECT received from " + clientAddress);
                        return;

                    case PacketType.INPUT_EVENT:
                        // Tái tạo sự kiện chuột/phím từ mảng 17 bytes nhị phân
                        try {
                            InputEventPacket event = InputEventPacket.fromBytes(packet.payload());
                            robotReplayer.processEvent(event);
                        } catch (Exception e) {
                            System.err.println("[ClientHandler] Lỗi xử lý INPUT_EVENT: " + e.getMessage());
                        }
                        break;


                    case PacketType.FILE_META: {
                        String payload = packet.payloadAsString();
                        String[] parts = payload.split("\\|", 2);
                        receivingFileName = parts.length > 0 ? parts[0] : "received_file";
                        receivingFileSize = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                        receivedBytes = 0;

                        java.io.File downloadDir = new java.io.File("downloads");
                        if (!downloadDir.exists()) downloadDir.mkdirs();

                        java.io.File outputFile = new java.io.File(downloadDir, receivingFileName);
                        fileOutputStream = new java.io.FileOutputStream(outputFile);
                        System.out.println("[ClientHandler] Bắt đầu nhận file: " + receivingFileName + " (" + receivingFileSize + " bytes)");
                        break;
                    }

                    case PacketType.FILE_CHUNK: {
                        if (fileOutputStream != null) {
                            byte[] chunk = packet.payload();
                            fileOutputStream.write(chunk);
                            receivedBytes += chunk.length;
                        }
                        break;
                    }

                    case PacketType.FILE_END: {
                        if (fileOutputStream != null) {
                            fileOutputStream.flush();
                            fileOutputStream.close();
                            fileOutputStream = null;
                            System.out.println("[ClientHandler] HOÀN TẤT NHẬN FILE: downloads/" + receivingFileName + " (" + receivedBytes + " bytes)");
                        }
                        break;
                    }

                    default:
                        System.out.println("[ClientHandler] Unhandled packet type: " + packet);
                        break;
                }
            } catch (SocketTimeoutException e) {
                System.err.println("[ClientHandler] Heartbeat TIMEOUT (" + (NetworkConstants.HEARTBEAT_TIMEOUT_MS / 1000)
                        + "s) from " + clientAddress + ". Client considered disconnected.");
                break;
            } catch (EOFException | SocketException e) {
                System.out.println("[ClientHandler] Client disconnected during session: " + clientAddress);
                break;
            }
        }
    }

    private void closeSocket() {
        // Dừng luồng phát video để tránh zombie thread và memory leak
        if (videoStreamSender != null) {
            try {
                videoStreamSender.stop();
            } catch (Exception e) {
                System.err.println("[ClientHandler] Lỗi dừng video stream: " + e.getMessage());
            }
        }


        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();
                System.out.println("[ClientHandler] Đã đóng file do ngắt kết nối giữa chừng.");
            } catch (IOException ignored) {}
            fileOutputStream = null;
        }

        try {
            if (!clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[ClientHandler] Error closing socket: " + e.getMessage());
        }
        System.out.println("[ClientHandler] Connection closed for: " + clientAddress);
    }
}
