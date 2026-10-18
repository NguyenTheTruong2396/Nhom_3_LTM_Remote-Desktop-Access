package com.remote.common.network;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a single TLV packet from a {@link DataInputStream}.
 *
 * TLV format: [1 byte Type][4 byte Length (int)][N byte Value]
 *
 * Usage:
 * <pre>
 *     Packet packet = PacketParser.read(dis);
 *     if (packet.type() == PacketType.AUTH_REQ) {
 *         String payload = packet.payloadAsString();
 *         ...
 *     }
 * </pre>
 */
public final class PacketParser {

    private PacketParser() {}

    /**
     * Immutable container for one decoded TLV packet.
     */
    public static final class Packet {
        private final byte type;
        private final byte[] payload;

        Packet(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        /** The raw packet type byte (see {@link PacketType}). */
        public byte type() {
            return type;
        }

        /** The raw payload bytes (never {@code null}, may be empty). */
        public byte[] payload() {
            return payload;
        }

        /** Payload decoded as UTF-8 string. */
        public String payloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }

        /** Payload length in bytes. */
        public int length() {
            return payload.length;
        }

        /**
         * Convenience: returns the first byte of the payload as an unsigned int.
         * Used for AUTH_RESP (1 = OK, 0 = Fail).
         */
        public int firstByteAsInt() {
            if (payload.length == 0) {
                throw new IllegalStateException("Payload is empty");
            }
            return payload[0] & 0xFF;
        }

        @Override
        public String toString() {
            return "Packet{type=0x" + String.format("%02X", type & 0xFF)
                    + ", length=" + payload.length + "}";
        }
    }

    /**
     * Blocks until a full TLV packet is read from the stream.
     *
     * @param in the input stream to read from
     * @return a decoded {@link Packet}
     * @throws IOException if the stream is closed or an I/O error occurs
     */
    public static Packet read(DataInputStream in) throws IOException {
        byte type = in.readByte();
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Invalid packet: negative length " + length);
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            in.readFully(payload);
        }
        return new Packet(type, payload);
    }
}
