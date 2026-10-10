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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.remote.server.nativeapi.JniBridge;
import com.remote.server.nativeapi.NativeCapture;

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

    public ClientHandler(Socket socket, SessionManager sessionManager) {
        this.clientSocket = socket;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
        this.sessionManager = sessionManager;
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
     * Week 2: placeholder packet loop — will send one FRAME_DATA in Step 6.
     */
    private void onAuthenticated(DataInputStream dis, DataOutputStream dos) throws IOException {
        System.out.println("[ClientHandler] Session established, entering packet loop: " + clientAddress);

        // --- Step 6: Send initial frame ---
        try {
            byte[] jpegBytes;
            if (JniBridge.isLibraryLoaded()) {
                System.out.println("[ClientHandler] Capturing frame via JNI...");
                jpegBytes = NativeCapture.captureAndCompress(60);
            } else {
                System.out.println("[ClientHandler] JNI not loaded, using fallback test_screen.jpg...");
                Path testImagePath = Paths.get("..", "native-core", "src", "main", "cpp", "test_screen.jpg");
                if (Files.exists(testImagePath)) {
                    jpegBytes = Files.readAllBytes(testImagePath);
                } else {
                    System.out.println("[ClientHandler] test_screen.jpg not found at " + testImagePath.toAbsolutePath());
                    jpegBytes = new byte[] { (byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xD9 }; // Dummy empty JPEG
                }
            }
            PacketBuilder.writeFrameData(dos, jpegBytes);
            System.out.println("[ClientHandler] Sent initial FRAME_DATA (" + jpegBytes.length + " bytes) to " + clientAddress);
        } catch (Exception e) {
            System.err.println("[ClientHandler] Failed to send initial frame: " + e.getMessage());
        }
        // ----------------------------------

        while (!clientSocket.isClosed()) {
            try {
                Packet packet = PacketParser.read(dis);
                System.out.println("[ClientHandler] Received " + packet + " from " + clientAddress);

                switch (packet.type()) {
                    case PacketType.PING:
                        PacketBuilder.writeEmpty(dos, PacketType.PONG);
                        System.out.println("[ClientHandler] Sent PONG to " + clientAddress);
                        break;

                    case PacketType.DISCONNECT:
                        System.out.println("[ClientHandler] DISCONNECT received from " + clientAddress);
                        return;

                    case PacketType.INPUT_EVENT:
                        // Will be handled by Dev 4 — log for now
                        System.out.println("[ClientHandler] INPUT_EVENT: " + packet.payloadAsString());
                        break;

                    default:
                        System.out.println("[ClientHandler] Unhandled packet type: " + packet);
                        break;
                }
            } catch (EOFException | SocketException e) {
                System.out.println("[ClientHandler] Client disconnected during session: " + clientAddress);
                break;
            }
        }
    }

    private void closeSocket() {
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
