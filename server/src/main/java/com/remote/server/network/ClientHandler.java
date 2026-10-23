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
/**
 * Phase 5: Handles one Control Channel TCP connection.
 *
 * Responsibilities:
 *   - AUTH handshake: validates credentials, issues a stream token, and
 *     returns it in the AUTH_RESP payload ("OK|streamToken").
 *   - Post-auth loop: input events, heartbeat PING/PONG, file transfer,
 *     clipboard sync, graceful DISCONNECT.
 *
 * Video streaming is now handled exclusively by {@link VideoChannelHandler}
 * on the separate Video Channel (port 5001) after successful token pairing.
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final String clientAddress;
    private final SessionManager sessionManager;
    private final RobotEventReplayer robotReplayer;
    // NOTE: VideoStreamSender is no longer started here (Phase 5).
    //       It runs on VideoChannelHandler after stream-token pairing.


    private java.io.FileOutputStream fileOutputStream;
    private java.io.File receivingFile;
    private java.io.File receivingPartFile;
    private String receivingFileName;
    private long receivingFileSize;
    private long receivedBytes;
    private String expectedUploadChecksum;
    private volatile boolean uploadCancelled = false;

    private volatile boolean downloadCancelled = false;
    private Thread activeDownloadThread;

    private volatile boolean isSettingClipboard = false;
    private Thread clipboardMonitorThread;

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
            String sessionId = handleAuth(dis, dos);
            if (sessionId == null) {
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
     * Phase 5 change: on success the AUTH_RESP payload carries the stream token
     * that the client must present when opening the Video Channel.
     * Payload format: "OK|streamToken" (success) or "FAIL" (failure).
     *
     * @return the authenticated sessionId on success, {@code null} on failure
     */
    private String handleAuth(DataInputStream dis, DataOutputStream dos) throws IOException {
        Packet packet = PacketParser.read(dis);

        if (packet.type() != PacketType.AUTH_REQ) {
            System.err.println("[ClientHandler] Expected AUTH_REQ (0x01) but got: " + packet);
            PacketBuilder.writeText(dos, PacketType.AUTH_RESP, "FAIL");
            return null;
        }

        // Payload format: "sessionId|passcode"
        String payload   = packet.payloadAsString();
        String[] parts   = payload.split("\\|", 2);
        String sessionId = parts.length > 0 ? parts[0] : "";
        String passcode  = parts.length > 1 ? parts[1] : "";

        System.out.println("[ClientHandler] AUTH_REQ received — sessionId=" + sessionId
                + " from " + clientAddress);

        boolean ok = sessionManager.authenticate(sessionId, passcode);

        if (ok) {
            // Generate a one-time stream token and return it to the client
            String streamToken = sessionManager.generateStreamToken(sessionId);
            PacketBuilder.writeText(dos, PacketType.AUTH_RESP, "OK|" + streamToken);
            System.out.println("[ClientHandler] Auth SUCCESS for sessionId=" + sessionId
                    + ", stream token issued.");
            return sessionId;
        } else {
            PacketBuilder.writeText(dos, PacketType.AUTH_RESP, "FAIL");
            System.out.println("[ClientHandler] Auth FAILED  for sessionId=" + sessionId);
            return null;
        }
    }

    /**
     * Called after successful authentication.
     * Starts continuous video stream and processes incoming packets (including input events).
     */
     private void onAuthenticated(DataInputStream dis, DataOutputStream dos) throws IOException {
        System.out.println("[ClientHandler] Control session established for: " + clientAddress
                + " — awaiting Video Channel pairing on port "
                + sessionManager.getVideoPort());

        // Phase 5: VideoStreamSender no longer starts here.
        // It is launched by VideoChannelHandler once the client pairs the video socket.

        // Khởi động luồng giám sát Clipboard máy Server để đồng bộ sang Client
        startServerClipboardMonitor(dos);

        // Cài đặt timeout giám sát Heartbeat (10 giây không có packet -> ngắt kết nối)
        clientSocket.setSoTimeout(NetworkConstants.HEARTBEAT_TIMEOUT_MS);

        while (!clientSocket.isClosed()) {
            try {
                Packet packet = PacketParser.read(dis);

                switch (packet.type()) {
                    case PacketType.PING:
                        synchronized (dos) {
                            if (packet.payload() != null && packet.payload().length > 0) {
                                PacketBuilder.write(dos, PacketType.PONG, packet.payload());
                            } else {
                                PacketBuilder.writeEmpty(dos, PacketType.PONG);
                            }
                        }
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

                    case PacketType.DIR_REQ: {
                        // Remote Directory Browsing: Client yêu cầu danh sách file/thư mục
                        String reqPath = packet.payloadAsString();
                        handleDirectoryRequest(reqPath, dos);
                        break;
                    }

                    case PacketType.FILE_DOWNLOAD_REQ: {
                        // Client yêu cầu tải file từ Server về Client (Download 2 chiều)
                        String reqFilePath = packet.payloadAsString();
                        handleDownloadRequest(reqFilePath, dos);
                        break;
                    }

                    case PacketType.FILE_META: {
                        // Client gửi file lên Server (Upload 2 chiều)
                        String payload = packet.payloadAsString();
                        String[] parts = payload.split("\\|", 4);
                        receivingFileName = parts.length > 0 ? parts[0] : "received_file";
                        receivingFileSize = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                        expectedUploadChecksum = parts.length > 2 ? parts[2] : "";
                        String targetDir = parts.length > 3 ? parts[3] : "";

                        uploadCancelled = false;
                        receivedBytes = 0;

                        java.io.File downloadDir = (targetDir != null && !targetDir.trim().isEmpty())
                                ? new java.io.File(targetDir.trim())
                                : new java.io.File("downloads");
                        if (!downloadDir.exists()) downloadDir.mkdirs();

                        receivingFile = new java.io.File(downloadDir, receivingFileName);
                        receivingPartFile = new java.io.File(downloadDir, receivingFileName + ".part");
                        if (receivingPartFile.exists()) receivingPartFile.delete();

                        fileOutputStream = new java.io.FileOutputStream(receivingPartFile);
                        System.out.printf("[ClientHandler] Bắt đầu nhận file: %s (%d bytes, CRC: %s)\n",
                                receivingFileName, receivingFileSize, expectedUploadChecksum);
                        break;
                    }

                    case PacketType.FILE_CHUNK: {
                        if (fileOutputStream != null && !uploadCancelled) {
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

                            if (uploadCancelled) {
                                if (receivingPartFile != null && receivingPartFile.exists()) receivingPartFile.delete();
                                System.out.println("[ClientHandler] Đã dọn rác tệp hủy giữa chừng.");
                            } else {
                                // Xác thực Checksum CRC32
                                boolean ok = com.remote.common.util.FileChecksumUtil.verifyChecksum(receivingPartFile, expectedUploadChecksum);
                                if (ok) {
                                    if (receivingFile.exists()) receivingFile.delete();
                                    receivingPartFile.renameTo(receivingFile);
                                    System.out.println("[ClientHandler] HOÀN TẤT NHẬN FILE & CHECK OK: " + receivingFile.getAbsolutePath());
                                } else {
                                    if (receivingPartFile != null && receivingPartFile.exists()) receivingPartFile.delete();
                                    System.err.println("[ClientHandler] LỖI CHECK CHKECKSUM KHÔNG KHỚP! ĐÃ XÓA FILE RÁC.");
                                }
                            }
                        }
                        break;
                    }

                    case PacketType.FILE_CANCEL: {
                        System.out.println("[ClientHandler] Nhận tín hiệu HỦY truyền file.");
                        uploadCancelled = true;
                        downloadCancelled = true;
                        if (fileOutputStream != null) {
                            try { fileOutputStream.close(); } catch (Exception ignored) {}
                            fileOutputStream = null;
                        }
                        if (receivingPartFile != null && receivingPartFile.exists()) {
                            receivingPartFile.delete();
                        }
                        break;
                    }

                    case PacketType.CLIPBOARD_SYNC: {
                        // Nhận nội dung Clipboard từ Client -> Cập nhật vào Server Clipboard
                        String text = packet.payloadAsString();
                        setServerClipboardText(text);
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

    /**
     * Remote Directory Browsing: Trả về danh sách file và thư mục trên Server
     */
    private void handleDirectoryRequest(String reqPath, DataOutputStream dos) {
        try {
            java.io.File dir;
            if (reqPath == null || reqPath.trim().isEmpty() || "ROOTS".equalsIgnoreCase(reqPath.trim())) {
                // Trả về danh sách ổ đĩa (C:\, D:\, etc.)
                java.io.File[] roots = java.io.File.listRoots();
                StringBuilder sb = new StringBuilder("ROOTS\n");
                if (roots != null) {
                    for (java.io.File r : roots) {
                        com.remote.common.model.RemoteFileInfo info = new com.remote.common.model.RemoteFileInfo(
                                r.getAbsolutePath(), r.getAbsolutePath(), true, 0, 0);
                        sb.append(info.toTsvLine()).append("\n");
                    }
                }
                byte[] payload = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                synchronized (dos) {
                    PacketBuilder.write(dos, PacketType.DIR_RESP, payload);
                }
                return;
            }

            dir = new java.io.File(reqPath.trim());
            if (!dir.exists() || !dir.isDirectory()) {
                dir = new java.io.File("downloads");
                if (!dir.exists()) dir.mkdirs();
            }

            java.io.File[] files = dir.listFiles();
            StringBuilder sb = new StringBuilder(dir.getAbsolutePath()).append("\n");
            if (files != null) {
                // Sắp xếp: Thư mục lên trước, tệp sau
                java.util.Arrays.sort(files, (f1, f2) -> {
                    if (f1.isDirectory() && !f2.isDirectory()) return -1;
                    if (!f1.isDirectory() && f2.isDirectory()) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });

                for (java.io.File f : files) {
                    com.remote.common.model.RemoteFileInfo info = new com.remote.common.model.RemoteFileInfo(
                            f.getName(),
                            f.getAbsolutePath(),
                            f.isDirectory(),
                            f.isDirectory() ? 0 : f.length(),
                            f.lastModified()
                    );
                    sb.append(info.toTsvLine()).append("\n");
                }
            }

            byte[] payload = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            synchronized (dos) {
                PacketBuilder.write(dos, PacketType.DIR_RESP, payload);
            }
        } catch (Exception e) {
            System.err.println("[ClientHandler] Lỗi duyệt thư mục: " + e.getMessage());
        }
    }

    /**
     * Tải file từ Server gửi về cho Client (Download)
     */
    private void handleDownloadRequest(String remoteFilePath, DataOutputStream dos) {
        if (remoteFilePath == null || remoteFilePath.trim().isEmpty()) return;
        java.io.File file = new java.io.File(remoteFilePath.trim());
        if (!file.exists() || !file.isFile()) {
            System.err.println("[ClientHandler] Download file không tồn tại: " + remoteFilePath);
            return;
        }

        downloadCancelled = false;
        activeDownloadThread = new Thread(() -> {
            try {
                long totalBytes = file.length();
                String checksum = com.remote.common.util.FileChecksumUtil.calculateCRC32(file);

                // 1. Gửi FILE_META: "fileName|fileSize|checksum"
                String metaPayload = file.getName() + "|" + totalBytes + "|" + checksum;
                synchronized (dos) {
                    PacketBuilder.write(dos, PacketType.FILE_META, metaPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }

                // 2. Gửi từng khối FILE_CHUNK (16KB)
                byte[] buffer = new byte[16384];
                int bytesRead;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    while (!downloadCancelled && (bytesRead = fis.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);
                        synchronized (dos) {
                            PacketBuilder.write(dos, PacketType.FILE_CHUNK, chunk);
                        }
                        // Điều tiết nhẹ để nhường socket buffer cho Video Stream
                        try { Thread.sleep(2); } catch (Exception ignored) {}
                    }
                }

                if (downloadCancelled) {
                    synchronized (dos) {
                        PacketBuilder.writeEmpty(dos, PacketType.FILE_CANCEL);
                    }
                    System.out.println("[ClientHandler] Đã hủy tiến trình download: " + file.getName());
                } else {
                    // 3. Gửi FILE_END
                    synchronized (dos) {
                        PacketBuilder.writeEmpty(dos, PacketType.FILE_END);
                    }
                    System.out.println("[ClientHandler] Đã hoàn tất gửi file download: " + file.getName());
                }
            } catch (Exception e) {
                System.err.println("[ClientHandler] Lỗi truyền file download: " + e.getMessage());
            }
        }, "Server-File-Download-Thread");
        activeDownloadThread.setDaemon(true);
        activeDownloadThread.start();
    }

    /**
     * Cập nhật nội dung Clipboard trên máy Server
     */
    private void setServerClipboardText(String text) {
        if (text == null) return;
        try {
            isSettingClipboard = true;
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(text), null);
            System.out.println("[ClientHandler] Đã đồng bộ Clipboard từ Client vào Server.");
        } catch (Exception e) {
            System.err.println("[ClientHandler] Không thể gán Clipboard: " + e.getMessage());
        } finally {
            try { Thread.sleep(150); } catch (Exception ignored) {}
            isSettingClipboard = false;
        }
    }

    /**
     * Luồng giám sát Clipboard máy Server: Nếu người dùng Server sao chép văn bản mới,
     * tự động đẩy gói tin CLIPBOARD_SYNC sang Client.
     */
    private void startServerClipboardMonitor(DataOutputStream dos) {
        clipboardMonitorThread = new Thread(() -> {
            String lastText = "";
            while (!clientSocket.isClosed()) {
                try {
                    Thread.sleep(800);
                    if (!isSettingClipboard) {
                        java.awt.datatransfer.Clipboard clip = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                        if (clip.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                            String current = (String) clip.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                            if (current != null && !current.isEmpty() && !current.equals(lastText)) {
                                lastText = current;
                                synchronized (dos) {
                                    PacketBuilder.write(dos, PacketType.CLIPBOARD_SYNC,
                                            current.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                }
                                System.out.println("[ClientHandler] Đã gửi Clipboard từ Server sang Client.");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, "Server-Clipboard-Monitor");
        clipboardMonitorThread.setDaemon(true);
        clipboardMonitorThread.start();
    }

    private void closeSocket() {
        // Phase 5: VideoStreamSender lifecycle is managed by VideoChannelHandler.
        downloadCancelled = true;
        uploadCancelled = true;

        if (fileOutputStream != null) {
            try {
                fileOutputStream.flush();
                fileOutputStream.close();
                System.out.println("[ClientHandler] Đã đóng file do ngắt kết nối giữa chừng.");
            } catch (IOException ignored) {}
            fileOutputStream = null;
        }

        if (receivingPartFile != null && receivingPartFile.exists()) {
            receivingPartFile.delete();
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
