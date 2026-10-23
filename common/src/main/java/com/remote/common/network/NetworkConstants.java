package com.remote.common.network;

/**
 * Common network constants for TCP connection, timeouts, and heartbeat
 * intervals.
 *
 * Phase 5 — Dual-Socket Architecture:
 *   - Control Channel (port 5002): Low-latency channel for input events,
 *     keep-alive heartbeat, file transfer, clipboard sync, and authentication.
 *     Ready to be wrapped with TLS/SSL in a future phase.
 *   - Video Channel (port 5001): High-bandwidth channel dedicated to FRAME_DATA
 *     transmission (TCP_NODELAY=true, large send buffer).
 */
public final class NetworkConstants {

    private NetworkConstants() {
    }

    /**
     * @deprecated Use {@link #DEFAULT_CONTROL_PORT} instead.
     *             Kept for backward-compatibility during migration.
     */
    @Deprecated
    public static final int DEFAULT_PORT = 5002;

    /** Control channel port: low-latency TCP for auth, input events, heartbeat, file, clipboard. */
    public static final int DEFAULT_CONTROL_PORT = 5002;

    /** Video channel port: high-bandwidth TCP dedicated to FRAME_DATA stream. */
    public static final int DEFAULT_VIDEO_PORT = 5001;

    /** UDP broadcast / discovery port used by LAN auto-discovery. */
    public static final int DISCOVERY_PORT = 8889;

    /**
     * Interval between consecutive PING packets sent by Client (in milliseconds): 3
     * seconds
     */
    public static final int HEARTBEAT_INTERVAL_MS = 3000;

    /**
     * Socket read timeout on Server before assuming connection is dead (in
     * milliseconds): 10 seconds
     */
    public static final int HEARTBEAT_TIMEOUT_MS = 10000;

    /**
     * Time-to-live for a stream token issued after successful AUTH (in
     * milliseconds): 30 seconds.  The client must complete the VIDEO_HANDSHAKE_REQ
     * within this window or the token is discarded.
     */
    public static final int STREAM_TOKEN_TTL_MS = 30_000;
}
