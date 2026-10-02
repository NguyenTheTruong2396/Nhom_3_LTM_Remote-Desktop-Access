package com.remote.client.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientSocketManager {
    private static ClientSocketManager instance;
    private Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private boolean isConnected = false;

    // Packet Types (Phù hợp với Custom Binary Protocol của nhóm)
    public static final byte PACKET_TYPE_AUTH_REQ = 0x01;
    public static final byte PACKET_TYPE_AUTH_RESP = 0x02;

    private ClientSocketManager() {}

    public static synchronized ClientSocketManager getInstance() {
        if (instance == null) {
            instance = new ClientSocketManager();
        }
        return instance;
    }

    public boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());
            isConnected = true;
            return true;
        } catch (IOException e) {
            System.err.println("[Client] Connection Error: " + e.getMessage());
            isConnected = false;
            return false;
        }
    }

    /**
     * Logic gửi gói tin Xác thực (Session ID & Passcode)
     * Format gói tin đơn giản: [Type: 1B] [Length: 4B] [Payload: ID|Passcode]
     */
    public boolean authenticate(String sessionId, String passcode) {
        if (!isConnected || dos == null || dis == null) return false;

        try {
            // Chuẩn bị Payload
            String payloadStr = sessionId + "|" + passcode;
            byte[] payloadBytes = payloadStr.getBytes(StandardCharsets.UTF_8);

            // Gửi Packet Header + Body
            dos.writeByte(PACKET_TYPE_AUTH_REQ);     // 1 byte Type
            dos.writeInt(payloadBytes.length);       // 4 bytes Length
            dos.write(payloadBytes);                 // Body Payload
            dos.flush();

            // Nhận kết quả phản hồi từ Server
            byte respType = dis.readByte();
            int respLen = dis.readInt();
            byte statusResult = dis.readByte(); // 1: Thành công, 0: Thất bại

            return respType == PACKET_TYPE_AUTH_RESP && statusResult == 1;

        } catch (IOException e) {
            System.err.println("[Client] Auth Error: " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        try {
            isConnected = false;
            if (dis != null) dis.close();
            if (dos != null) dos.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Socket getSocket() { return socket; }
    public boolean isConnected() { return isConnected; }
}