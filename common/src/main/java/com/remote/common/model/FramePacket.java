package com.remote.common.model;

import com.remote.common.network.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Lớp đại diện cho gói tin Video Frame (khung hình màn hình).
 * Đóng gói dữ liệu ảnh nén JPEG kèm theo thông tin kích thước và timestamp
 * để truyền liên tục qua kết nối TCP Socket tới Client theo chuẩn TLV 5 bytes:
 *
 * Header:
 * [1 byte Type (0x03)] + [4 byte Length (int: 8 + N)]
 * Payload:
 * [8 byte Timestamp (long)] + [N byte JPEG Data]
 */
public class FramePacket {

    /** Mã loại gói tin frame (xem PacketType.FRAME_DATA). */
    public static final byte PACKET_TYPE_FRAME = PacketType.FRAME_DATA;

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
     * Đóng gói và ghi gói tin Frame ra Socket Output Stream theo chuẩn Header TLV 5
     * bytes:
     * [Type: 1 byte (0x03)] + [Length: 4 bytes (8 + N)] + [Timestamp: 8 bytes] +
     * [JPEG Data: N bytes]
     *
     * @param out DataOutputStream kết nối tới Socket
     * @throws IOException nếu xảy ra lỗi ghi mạng
     */
    public void writeTo(DataOutputStream out) throws IOException {
        int imageSize = (data != null) ? data.length : 0;
        int payloadLength = 8 + imageSize;

        // 1. Header TLV (5 bytes)
        out.writeByte(PACKET_TYPE_FRAME);
        out.writeInt(payloadLength);

        // 2. Payload (8 + N bytes)
        out.writeLong(timestamp);
        if (imageSize > 0) {
            out.write(data, 0, imageSize);
        }

        out.flush();
    }

    /**
     * Đọc và giải gói một FramePacket từ Socket Input Stream theo chuẩn TLV.
     *
     * @param in DataInputStream từ Socket
     * @return Đối tượng FramePacket chứa timestamp và mảng byte JPEG
     * @throws IOException nếu kết nối đứt hoặc gói tin không đúng định dạng
     */
    public static FramePacket readFrom(DataInputStream in) throws IOException {
        byte type = in.readByte();
        if (type != PACKET_TYPE_FRAME) {
            throw new IOException("Gói tin không hợp lệ, mong đợi FRAME_DATA (0x03) nhưng nhận được: " + type);
        }

        int length = in.readInt();
        if (length < 8) {
            throw new IOException("Kích thước frame payload không hợp lệ (nhỏ hơn 8 bytes timestamp): " + length);
        }
        if (length > 15 * 1024 * 1024) { // Giới hạn an toàn 15MB/frame
            throw new IOException("Kích thước frame bất thường: " + length + " bytes");
        }

        long timestamp = in.readLong();
        int imageSize = length - 8;
        byte[] payload = new byte[imageSize];
        if (imageSize > 0) {
            in.readFully(payload);
        }

        return new FramePacket(timestamp, payload);
    }
}
