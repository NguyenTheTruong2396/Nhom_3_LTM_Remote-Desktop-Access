package com.remote.client.core;

import com.remote.client.network.ConnectionManager;
import com.remote.client.ui.RemoteViewFrame;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketParser.Packet;
import com.remote.common.network.PacketType;

import javax.swing.SwingUtilities;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class ClientMain {
    private static final String DEFAULT_HOST = "10.208.144.181";
    private static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        try {
            System.out.println("=========================================");
            System.out.println("   Remote Desktop Client - Test Render   ");
            System.out.println("=========================================");

            ConnectionManager connection = new ConnectionManager(host, port);
            connection.connect();

            DataInputStream dis = connection.getInputStream();
            DataOutputStream dos = connection.getOutputStream();

            // 1. Nhập ID & Passcode hiển thị ở Terminal Server
            Scanner scanner = new Scanner(System.in);
            System.out.print("\n[Client] Nhập Session ID từ Server: ");
            String sessionId = scanner.nextLine().trim();

            System.out.print("[Client] Nhập Passcode từ Server: ");
            String passcode = scanner.nextLine().trim();

            // 2. Đóng gói AUTH_REQ theo chuẩn TLV: "sessionId|passcode"
            String authPayload = sessionId + "|" + passcode;
            byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);

            System.out.println("[Client] Đang gửi gói AUTH_REQ (0x01)...");
            PacketBuilder.write(dos, PacketType.AUTH_REQ, payloadBytes);

            // 3. Đọc phản hồi AUTH_RESP (0x02) từ Server
            Packet resp = PacketParser.read(dis);
            if (resp.type() == PacketType.AUTH_RESP) {
                boolean isSuccess = resp.payload().length > 0 && resp.payload()[0] == 1;

                if (isSuccess) {
                    System.out.println("[Client] Xác thực THÀNH CÔNG! Server bắt đầu phát Video Stream 30 FPS...");

                    // 4. Mở cửa sổ Render và bắt luồng ảnh
                    SwingUtilities.invokeLater(() -> {
                        RemoteViewFrame viewFrame = new RemoteViewFrame(connection);
                        viewFrame.setVisible(true);
                        
                        // Kích hoạt thread đọc FramePacket
                        viewFrame.startReceiving();
                    });
                } else {
                    System.err.println("[Client] Xác thực THẤT BẠI! Sai Session ID hoặc Passcode.");
                }
            } else {
                System.err.println("[Client] Gói tin phản hồi không hợp lệ: " + resp);
            }

        } catch (Exception e) {
            System.err.println("[ClientMain] Lỗi trong quá trình kết nối/xác thực: " + e.getMessage());
            e.printStackTrace();
        }
    }
}