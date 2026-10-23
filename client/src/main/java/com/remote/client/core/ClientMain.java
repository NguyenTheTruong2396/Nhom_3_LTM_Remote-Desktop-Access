package com.remote.client.core;

import com.remote.client.network.ConnectionManager;
import com.remote.client.network.HeartbeatSender;
import com.remote.client.ui.RemoteViewFrame;
import com.remote.common.network.NetworkConstants;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketParser.Packet;
import com.remote.common.network.PacketType;

import javax.swing.SwingUtilities;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Phase 5 — Dual-Socket Client Entry Point.
 *
 * Connection sequence:
 *  1. Connect Control Channel → port 5002.
 *  2. Send AUTH_REQ; read AUTH_RESP "OK|streamToken" or "FAIL".
 *  3. If OK, open Video Channel → port 5001, send VIDEO_HANDSHAKE_REQ.
 *  4. Start HeartbeatSender on Control channel.
 *  5. Open RemoteViewFrame with separate Control + Video streams.
 */
public class ClientMain {
    private static final String DEFAULT_HOST         = "127.0.0.1";
    private static final int    DEFAULT_CONTROL_PORT = NetworkConstants.DEFAULT_CONTROL_PORT;
    private static final int    DEFAULT_VIDEO_PORT   = NetworkConstants.DEFAULT_VIDEO_PORT;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;

        try {
            System.out.println("=========================================");
            System.out.println("   Remote Desktop Client - Dual-Socket  ");
            System.out.println("=========================================");

            Scanner scanner = new Scanner(System.in);
            System.out.print("[Client] Nhập IP Server (Enter để dùng 127.0.0.1): ");
            String inputHost = scanner.nextLine().trim();
            if (!inputHost.isEmpty()) {
                host = inputHost;
            }

            // 1. Tạo ConnectionManager với 2 port
            ConnectionManager connection = new ConnectionManager(host,
                    DEFAULT_CONTROL_PORT, DEFAULT_VIDEO_PORT);

            // 2. Kết nối Control Channel
            connection.connectControl();

            DataInputStream  dis = connection.getControlInputStream();
            DataOutputStream dos = connection.getControlOutputStream();

            // 3. Nhập thông tin xác thực từ Server terminal
            System.out.print("\n[Client] Nhập Session ID từ Server: ");
            String sessionId = scanner.nextLine().trim();
            System.out.print("[Client] Nhập Passcode từ Server: ");
            String passcode = scanner.nextLine().trim();

            // 4. Thực hiện xác thực — nhận streamToken trong phản hồi
            AuthResult auth = authenticate(connection, sessionId, passcode);
            if (!auth.success) {
                System.err.println("[Client] Xác thực THẤT BẠI! Vui lòng kiểm tra lại Session ID hoặc Passcode.");
                connection.close();
                return;
            }

            System.out.println("[Client] Xác thực THÀNH CÔNG! Stream token nhận được.");

            // 5. Mở Video Channel với stream token
            try {
                connection.connectVideo(sessionId, auth.streamToken);
            } catch (Exception e) {
                System.err.println("[Client] Không thể ghép cặp Video Channel: " + e.getMessage());
                connection.close();
                return;
            }

            System.out.println("[Client] Video Channel đã ghép cặp! Server bắt đầu phát Video Stream 30 FPS...");

            // 6. Khởi động HeartbeatSender trên Control Channel
            final HeartbeatSender[] heartbeatHolder = new HeartbeatSender[1];
            heartbeatHolder[0] = new HeartbeatSender(connection.getControlOutputStream());
            heartbeatHolder[0].start();

            // 7. Mở cửa sổ Render
            SwingUtilities.invokeLater(() -> {
                RemoteViewFrame viewFrame = new RemoteViewFrame(connection);
                viewFrame.setVisible(true);

                // Thiết lập cơ chế tự động Reconnect khi đứt kết nối
                viewFrame.setDisconnectCallback(() -> {
                    System.out.println("\n[Client] =========================================");
                    System.out.println("[Client] PHÁT HIỆN MẤT KẾT NỐI VỚI SERVER!");
                    System.out.println("[Client] Đang tự động kết nối lại sau mỗi 2 giây...");
                    System.out.println("[Client] =========================================");

                    if (heartbeatHolder[0] != null) {
                        heartbeatHolder[0].stop();
                    }

                    new Thread(() -> {
                        // Reconnect Control Channel
                        boolean reconnected = connection.reconnect(0, 2000);
                        if (reconnected) {
                            try {
                                AuthResult reAuth = authenticate(connection, sessionId, passcode);
                                if (reAuth.success) {
                                    System.out.println("[Client] Tái xác thực THÀNH CÔNG!");
                                    // Re-pair video channel
                                    connection.connectVideo(sessionId, reAuth.streamToken);
                                    System.out.println("[Client] Video Channel đã ghép cặp lại!");

                                    viewFrame.updateConnection(connection);
                                    viewFrame.startReceiving();

                                    heartbeatHolder[0] = new HeartbeatSender(connection.getControlOutputStream());
                                    heartbeatHolder[0].start();
                                } else {
                                    System.err.println("[Client] Tái xác thực thất bại. Server có thể đã tạo phiên mới.");
                                }
                            } catch (Exception ex) {
                                System.err.println("[Client] Lỗi khi tái kết nối: " + ex.getMessage());
                            }
                        }
                    }, "Client-Reconnect-Thread").start();
                });

                // Kích hoạt thread đọc Frame từ Video Channel và Control events
                viewFrame.startReceiving();

                // Dừng Heartbeat và đóng kết nối khi người dùng tắt cửa sổ
                viewFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        if (heartbeatHolder[0] != null) {
                            heartbeatHolder[0].stop();
                        }
                        connection.close();
                        System.out.println("[Client] Đã dừng Heartbeat và đóng kết nối.");
                    }
                });
            });

        } catch (Exception e) {
            System.err.println("[ClientMain] Lỗi trong quá trình kết nối/xác thực: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---- Auth helper ---------------------------------------------------------

    /** Result of an authentication attempt. */
    private static final class AuthResult {
        final boolean success;
        final String  streamToken;

        AuthResult(boolean success, String streamToken) {
            this.success     = success;
            this.streamToken = streamToken;
        }
    }

    /**
     * Sends AUTH_REQ and parses AUTH_RESP from the Control Channel.
     *
     * Phase 5 response format:
     *   "OK|streamToken"  → authenticated, streamToken carries pairing credential
     *   "FAIL"            → authentication failed
     *
     * @return AuthResult with success flag and stream token (empty if failed)
     */
    private static AuthResult authenticate(ConnectionManager connection,
                                           String sessionId, String passcode) throws IOException {
        String authPayload = sessionId + "|" + passcode;
        byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);

        System.out.println("[Client] Đang gửi gói AUTH_REQ (0x01)...");
        PacketBuilder.write(connection.getControlOutputStream(), PacketType.AUTH_REQ, payloadBytes);

        Packet resp = PacketParser.read(connection.getControlInputStream());
        if (resp.type() != PacketType.AUTH_RESP) {
            return new AuthResult(false, "");
        }

        String body = resp.payloadAsString();
        if (body.startsWith("OK|")) {
            String token = body.substring(3); // "OK|<token>" → extract token
            return new AuthResult(true, token);
        }
        // Backward-compat: legacy server returns a single byte 0x01 for success
        if (resp.payload().length > 0 && resp.payload()[0] == 1) {
            return new AuthResult(true, "");
        }
        return new AuthResult(false, "");
    }
}