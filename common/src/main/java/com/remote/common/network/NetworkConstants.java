package com.remote.common.network;

/**
 * Common network constants for TCP connection, timeouts, and heartbeat
 * intervals.
 */
public final class NetworkConstants {

    private NetworkConstants() {
    }

    /** Default server listening port */
    public static final int DEFAULT_PORT = 8888;

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
}
