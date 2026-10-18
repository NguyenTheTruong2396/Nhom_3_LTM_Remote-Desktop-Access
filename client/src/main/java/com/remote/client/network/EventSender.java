package com.remote.client.network;

import com.remote.common.model.InputEventPacket;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketType;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Đảm nhiệm việc gửi các sự kiện chuột và bàn phím từ Client lên Server.
 * Sử dụng gói tin nhị phân Compact Binary (17 bytes) truyền trực tiếp qua
 * DataOutputStream theo Custom TLV Protocol (Type 0x04).
 */
public class EventSender {

    private final DataOutputStream out;

    public EventSender(DataOutputStream out) {
        this.out = out;
    }

    /**
     * Gửi một InputEventPacket lên Server.
     * Đồng bộ hóa để đảm bảo không bị xáo trộn byte khi nhiều sự kiện gửi dồn dập.
     *
     * @param packet Đối tượng sự kiện phím hoặc chuột
     */
    public void send(InputEventPacket packet) {
        if (packet == null || out == null) {
            return;
        }

        try {
            byte[] payload = packet.toBytes();
            // Dùng chung khóa synchronized trên đối tượng out để không đè byte với FileSender
            synchronized (out) {
                PacketBuilder.write(out, PacketType.INPUT_EVENT, payload);
            }
        } catch (IOException e) {
            System.err.println("[EventSender] Lỗi gửi sự kiện phím/chuột: " + e.getMessage());
        }
    }
}
