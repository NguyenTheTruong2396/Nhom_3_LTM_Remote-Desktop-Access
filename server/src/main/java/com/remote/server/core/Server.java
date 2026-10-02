package com.remote.server.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;


public class Server {
     public static void main(String[] args) {
        int port = 8888;
        System.out.println("[Server] Launching emulator server on port " + port + "...");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] A client has just connected from...: " + clientSocket.getRemoteSocketAddress());

                // Xử lý gói tin xác thực trong thread riêng
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket socket) {
        try (
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream())
        ) {
            // Đọc Header gói tin Auth Request
            byte packetType = dis.readByte();
            int length = dis.readInt();
            
            byte[] payloadBytes = new byte[length];
            dis.readFully(payloadBytes);
            
            String payload = new String(payloadBytes, StandardCharsets.UTF_8);
            System.out.println("[MockServer] Nhận gói tin Auth: Type=" + packetType + " | Payload=" + payload);

            // Tách ID và Passcode
            String[] parts = payload.split("\\|");
            String sessionId = parts.length > 0 ? parts[0] : "";
            String passcode = parts.length > 1 ? parts[1] : "";

            // Giả lập logic kiểm tra: Passcode là "123456" thì chấp nhận
            byte statusResult = (byte) ("123456".equals(passcode) ? 1 : 0);

            // Gửi Auth Response về Client
            dos.writeByte(0x02);          // Type 0x02 = AUTH_RESP
            dos.writeInt(1);             // Length = 1 byte
            dos.writeByte(statusResult);  // 1: Thành công, 0: Thất bại
            dos.flush();

            System.out.println("[MockServer] Đã trả về kết quả Auth Status: " + statusResult);

        } catch (IOException e) {
            System.err.println("[MockServer] Lỗi kết nối Client: " + e.getMessage());
        }
    }
}
