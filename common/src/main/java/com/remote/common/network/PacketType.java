package com.remote.common.network;

/**
 * Defines the TLV packet types used in the Remote Desktop custom protocol.
 *
 * Format: [1 byte Type][4 byte Length (int)][N byte Value]
 *
 * Packet Types:
 *   0x01 AUTH_REQ    — Client → Server: "sessionId|passcode" (UTF-8)
 *   0x02 AUTH_RESP   — Server → Client: 1 byte (1=OK, 0=Fail)
 *   0x03 FRAME_DATA  — Server → Client: raw JPEG byte array
 *   0x04 INPUT_EVENT — Client → Server: "type|x|y|keycode" (UTF-8)
 *   0x05 PING        — Bidirectional:   empty payload (Length=0)
 *   0x06 PONG        — Bidirectional:   empty payload (Length=0)
 *   0xFF DISCONNECT  — Bidirectional:   empty payload (Length=0)
 */
public final class PacketType {

    private PacketType() {}

    /** Client → Server: authentication request carrying "sessionId|passcode". */
    public static final byte AUTH_REQ    = 0x01;

    /** Server → Client: authentication response, 1 byte payload (1=OK, 0=Fail). */
    public static final byte AUTH_RESP   = 0x02;

    /** Server → Client: compressed JPEG frame bytes. */
    public static final byte FRAME_DATA  = 0x03;

    /** Client → Server: input event "type|x|y|keycode" (UTF-8). */
    public static final byte INPUT_EVENT = 0x04;

    /** Bidirectional keep-alive ping, empty payload. */
    public static final byte PING        = 0x05;

    /** Bidirectional keep-alive pong, empty payload. */
    public static final byte PONG        = 0x06;

    /** Bidirectional graceful disconnect signal, empty payload. */
    public static final byte DISCONNECT  = (byte) 0xFF;


    /** Client → Server / Server → Client: Thông tin file sắp gửi ("fileName|fileSize|checksum") */
    public static final byte FILE_META         = 0x07;

    /** Client → Server / Server → Client: Khối dữ liệu file (8KB-32KB raw bytes) */
    public static final byte FILE_CHUNK        = 0x08;

    /** Client → Server / Server → Client: Tín hiệu kết thúc truyền file */
    public static final byte FILE_END          = 0x09;

    /** Client → Server: Yêu cầu danh sách file/thư mục ("remotePath") */
    public static final byte DIR_REQ           = 0x0A;

    /** Server → Client: Kết quả danh sách file/thư mục */
    public static final byte DIR_RESP          = 0x0B;

    /** Client → Server: Yêu cầu tải file từ Server về Client ("remoteFilePath") */
    public static final byte FILE_DOWNLOAD_REQ = 0x0C;

    /** Hai chiều: Tín hiệu hủy truyền file ngang */
    public static final byte FILE_CANCEL       = 0x0E;

    /** Hai chiều: Đồng bộ nội dung văn bản Clipboard */
    public static final byte CLIPBOARD_SYNC    = 0x10;

    // ---- Video Channel Handshake (Phase 5) -----------------------------------

    /**
     * Client → Server (Video Channel): Video handshake request.
     * Payload format: "sessionId|streamToken" (UTF-8).
     * Sent on the second connection (port 5001) immediately after TCP connect.
     */
    public static final byte VIDEO_HANDSHAKE_REQ  = 0x20;

    /**
     * Server → Client (Video Channel): Video handshake response.
     * Payload: 1 byte — 1 = paired successfully, 0 = token invalid/expired.
     */
    public static final byte VIDEO_HANDSHAKE_RESP = 0x21;
}
