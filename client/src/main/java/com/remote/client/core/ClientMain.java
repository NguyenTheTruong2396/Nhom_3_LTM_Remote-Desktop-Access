package com.remote.client.core;

import com.remote.client.network.ConnectionManager;
import com.remote.client.network.HeartbeatSender;
import com.remote.client.ui.RemoteViewFrame;
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

public class ClientMain {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        try {
            System.out.println("=========================================");
            System.out.println("   Remote Desktop Client - Test Render   ");
            System.out.println("=========================================");

            Scanner scanner = new Scanner(System.in);
            System.out.print("[Client] Nhập IP Server (Enter để dùng 127.0.0.1): ");
            String inputHost = scanner.nextLine().trim();
            if (!inputHost.isEmpty()) {
                host = inputHost;
            }

            ConnectionManager connection = new ConnectionManager(host, port);
            connection.connect();

            DataInputStream dis = connection.getInputStream();
            DataOutputStream dos = connection.getOutputStream();

            // 1. Nhập ID & Passcode hiển thị ở Terminal Server
            System.out.print("\n[Client] Nhập Session ID từ Server: ");
            String sessionId = scanner.nextLine().trim();

            System.out.print("[Client] Nhập Passcode từ Server: ");
            String passcode = scanner.nextLine().trim();

            // 2. Thực hiện xác thực ban đầu
            if (!authenticate(connection, sessionId, passcode)) {
                System.err.println("[Client] Xác thực THẤT BẠI! Vui lòng kiểm tra lại Session ID hoặc Passcode.");
                connection.close();
                return;
            }

            System.out.println("[Client] Xác thực THÀNH CÔNG! Server bắt đầu phát Video Stream 30 FPS...");

            // 3. Khởi động HeartbeatSender
            final HeartbeatSender[] heartbeatHolder = new HeartbeatSender[1];
            heartbeatHolder[0] = new HeartbeatSender(connection.getOutputStream());
            heartbeatHolder[0].start();

            // 4. Mở cửa sổ Render
            SwingUtilities.invokeLater(() -> {
                RemoteViewFrame viewFrame = new RemoteViewFrame(connection);
                viewFrame.setVisible(true);

                // Thiết lập cơ chế tự động Reconnect khi đứt kết nối
                viewFrame.setDisconnectCallback(() -> {
                    System.out.println("\n[Client] =========================================");
                    System.out.println("[Client] PHÁT HIỆN MẤT KẾT NỐI TCP VỚI SERVER!");
                    System.out.println("[Client] Đang tự động kết nối lại sau mỗi 2 giây...");
                    System.out.println("[Client] =========================================");

                    if (heartbeatHolder[0] != null) {
                        heartbeatHolder[0].stop();
                    }

                    new Thread(() -> {
                        boolean reconnected = connection.reconnect(0, 2000); // Thử lại liên tục mỗi 2s
                        if (reconnected) {
                            try {
                                if (authenticate(connection, sessionId, passcode)) {
                                    System.out.println("[Client] Tái xác thực THÀNH CÔNG! Đang khôi phục phiên điều khiển...");
                                    viewFrame.updateConnection(connection);
                                    viewFrame.startReceiving();

                                    heartbeatHolder[0] = new HeartbeatSender(connection.getOutputStream());
                                    heartbeatHolder[0].start();
                                } else {
                                    System.err.println("[Client] Tái xác thực thất bại. Server có thể đã tạo phiên mới.");
                                }
                            } catch (Exception ex) {
                                System.err.println("[Client] Lỗi khi tái xác thực: " + ex.getMessage());
                            }
                        }
                    }, "Client-Reconnect-Thread").start();
                });

                // Kích hoạt thread đọc FramePacket
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

    /**
     * Gửi gói AUTH_REQ (0x01) và đọc phản hồi AUTH_RESP (0x02) từ Server.
     */
    private static boolean authenticate(ConnectionManager connection, String sessionId, String passcode) throws IOException {
        String authPayload = sessionId + "|" + passcode;
        byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);

        System.out.println("[Client] Đang gửi gói AUTH_REQ (0x01)...");
        PacketBuilder.write(connection.getOutputStream(), PacketType.AUTH_REQ, payloadBytes);

        Packet resp = PacketParser.read(connection.getInputStream());
        if (resp.type() == PacketType.AUTH_RESP) {
            return resp.payload().length > 0 && resp.payload()[0] == 1;
        }
        return false;
    }
}