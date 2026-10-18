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

    /** Client → Server: Thông tin file sắp gửi ("fileName|fileSize") */
    public static final byte FILE_META  = 0x07;

    /** Client → Server: Khối dữ liệu file (8KB raw bytes) */
    public static final byte FILE_CHUNK = 0x08;

    /** Client → Server: Tín hiệu kết thúc truyền file */
    public static final byte FILE_END   = 0x09;
}
