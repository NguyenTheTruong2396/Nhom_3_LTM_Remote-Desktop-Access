package com.remote.server.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Lớp đại diện cho gói tin Video Frame (khung hình màn hình).
 * Đóng gói dữ liệu ảnh nén JPEG kèm theo thông tin kích thước và timestamp
 * để truyền liên tục qua kết nối TCP Socket tới Client theo tài liệu kiến trúc.
 */
public class FramePacket {

    /**
     * Mã định danh loại gói tin Video Frame trong Custom Protocol TCP.
     * 0x01: AUTH_REQ | 0x02: AUTH_RESP | 0x03: VIDEO_FRAME | 0x04: INPUT_EVENT
     */
    public static final byte PACKET_TYPE_FRAME = 0x03;

    private final long timestamp;
    private final byte[] data;

    /**
     * Khởi tạo gói tin với dữ liệu frame ảnh và gán timestamp thời điểm hiện tại.
     *
     * @param data Mảng byte ảnh JPEG nén
     */
    public FramePacket(byte[] data) {
        this(System.currentTimeMillis(), data);
    }

    /**
     * Khởi tạo gói tin với timestamp và dữ liệu frame ảnh cụ thể.
     *
     * @param timestamp Thời gian chụp khung hình (milliseconds)
     * @param data      Mảng byte ảnh JPEG nén
     */
    public FramePacket(long timestamp, byte[] data) {
        this.timestamp = timestamp;
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public byte[] getData() {
        return data;
    }

    public int getLength() {
        return data != null ? data.length : 0;
    }

    /**
     * Đóng gói và ghi gói tin Frame ra Socket Output Stream theo Binary Protocol:
     * [Packet Type (1 byte)] + [Timestamp (8 bytes)] + [Data Length (4 bytes)] + [JPEG Payload (N bytes)]
     *
     * @param out DataOutputStream kết nối tới Client
     * @throws IOException nếu xảy ra lỗi ghi mạng
     */
    public void writeTo(DataOutputStream out) throws IOException {
        if (data == null || data.length == 0) {
            return;
        }

        // 1. Header: Loại gói tin
        out.writeByte(PACKET_TYPE_FRAME);

        // 2. Header: Thời gian chụp (dùng để Client đo độ trễ mạng - latency)
        out.writeLong(timestamp);

        // 3. Header: Độ dài mảng byte JPEG
        out.writeInt(data.length);

        // 4. Payload: Dữ liệu ảnh JPEG nén
        out.write(data, 0, data.length);

        // Đẩy ngay dữ liệu đi để giảm độ trễ
        out.flush();
    }

    /**
     * Đọc và giải gói một FramePacket từ Socket Input Stream (tiện ích cho phía Client đọc).
     *
     * @param in DataInputStream từ Server
     * @return Đối tượng FramePacket chứa timestamp và mảng byte JPEG
     * @throws IOException nếu kết nối đứt hoặc gói tin không đúng định dạng
     */
    public static FramePacket readFrom(DataInputStream in) throws IOException {
        byte type = in.readByte();
        if (type != PACKET_TYPE_FRAME) {
            throw new IOException("Gói tin không hợp lệ, mong đợi VIDEO_FRAME (0x03) nhưng nhận được: " + type);
        }

        long timestamp = in.readLong();
        int length = in.readInt();

        if (length < 0 || length > 10 * 1024 * 1024) { // Giới hạn an toàn 10MB/frame
            throw new IOException("Kích thước frame bất thường: " + length + " bytes");
        }

        byte[] payload = new byte[length];
        in.readFully(payload);

        return new FramePacket(timestamp, payload);
    }
}
