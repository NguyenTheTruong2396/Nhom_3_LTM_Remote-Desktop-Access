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
    private volatile boolean stopped = false;

    public EventSender(DataOutputStream out) {
        this.out = out;
    }

    /**
     * Gửi một InputEventPacket lên Server.
     * Đồng bộ hóa để đảm bảo không bị xáo trộn byte khi nhiều sự kiện gửi dồn dập.
     *
     * @param packet Đối tượng sự kiện phím hoặc chuột
     */
    public synchronized void send(InputEventPacket packet) {
        if (packet == null || out == null || stopped) {
            return;
        }

        try {
            byte[] payload = packet.toBytes();

            // Dùng chung khóa synchronized trên đối tượng out để không đè byte với FileSender
            synchronized (out) {
                PacketBuilder.write(out, PacketType.INPUT_EVENT, payload);
            }

           
        } catch (IOException e) {
            if (!stopped) {
                stopped = true;
                System.err.println("[EventSender] Socket đóng, dừng gửi sự kiện: " + e.getMessage());
            }
        }
    }

    /** Đánh dấu EventSender đã dừng — không gửi thêm events và không log lỗi nữa. */
    public void stop() {
        stopped = true;
    }
}
