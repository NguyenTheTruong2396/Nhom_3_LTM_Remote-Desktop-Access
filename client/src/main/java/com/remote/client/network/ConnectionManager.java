package com.remote.client.network;

import com.remote.common.network.NetworkConstants;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Phase 5 — Dual-Socket Connection Manager.
 *
 * Manages two independent TCP connections to the server:
 *   - <b>Control Socket</b> ({@value NetworkConstants#DEFAULT_CONTROL_PORT}):
 *     Used for authentication, input events, heartbeat, file transfer, and
 *     clipboard sync.  Low-latency; {@code TCP_NODELAY=true}.
 *   - <b>Video Socket</b> ({@value NetworkConstants#DEFAULT_VIDEO_PORT}):
 *     Used exclusively for receiving FRAME_DATA packets.  Large receive buffer;
 *     opened after successful authentication and stream-token exchange.
 *
 * Typical usage:
 * <pre>
 *   ConnectionManager cm = new ConnectionManager(host);
 *   cm.connectControl();
 *   // ... authenticate, receive stream token ...
 *   cm.connectVideo(sessionId, streamToken);
 * </pre>
 */
public class ConnectionManager implements AutoCloseable {

    private final String host;
    private final int controlPort;
    private final int videoPort;

    // Control channel
    private Socket         controlSocket;
    private DataInputStream  controlInputStream;
    private DataOutputStream controlOutputStream;

    // Video channel
    private Socket         videoSocket;
    private DataInputStream  videoInputStream;

    // -------------------------------------------------------------------------

    /**
     * Creates a ConnectionManager using the default ports from
     * {@link NetworkConstants}.
     *
     * @param host server host name or IP
     */
    public ConnectionManager(String host) {
        this(host, NetworkConstants.DEFAULT_CONTROL_PORT, NetworkConstants.DEFAULT_VIDEO_PORT);
    }

    /**
     * Creates a ConnectionManager with explicit port numbers.
     *
     * @param host        server host name or IP
     * @param controlPort port for the Control Channel
     * @param videoPort   port for the Video Channel
     */
    public ConnectionManager(String host, int controlPort, int videoPort) {
        this.host        = host;
        this.controlPort = controlPort;
        this.videoPort   = videoPort;
    }

    /**
     * Legacy single-port constructor kept for backward compatibility.
     * Maps the given port to the control channel; video port defaults to
     * {@link NetworkConstants#DEFAULT_VIDEO_PORT}.
     *
     * @param host server host name or IP
     * @param port control channel port (previously "default port")
     * @deprecated Use {@link #ConnectionManager(String)} or
     *             {@link #ConnectionManager(String, int, int)} instead.
     */
    @Deprecated
    public ConnectionManager(String host, int port) {
        this(host, port, NetworkConstants.DEFAULT_VIDEO_PORT);
    }

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;

    // ---- Control Channel -----------------------------------------------------

    /**
     * Establishes a TCP connection to the server's Control Channel with default timeout (2s).
     *
     * @throws IOException if connection fails
     */
    public void connectControl() throws IOException {
        connectControl(DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Establishes a TCP connection to the server's Control Channel with explicit timeout.
     *
     * @param timeoutMs connect timeout in milliseconds
     * @throws IOException if connection fails
     */
    public void connectControl(int timeoutMs) throws IOException {
        System.out.println("[ConnectionManager] Connecting CONTROL channel to "
                + host + ":" + controlPort + " (timeout: " + timeoutMs + "ms)...");
        controlSocket = new Socket();
        controlSocket.connect(new InetSocketAddress(host, controlPort), timeoutMs);
        controlSocket.setTcpNoDelay(true);
        controlInputStream  = new DataInputStream(controlSocket.getInputStream());
        controlOutputStream = new DataOutputStream(controlSocket.getOutputStream());
        System.out.println("[ConnectionManager] CONTROL channel connected.");
    }

    /**
     * Legacy alias for {@link #connectControl()}.
     *
     * @throws IOException if connection fails
     * @deprecated Use {@link #connectControl()} explicitly.
     */
    @Deprecated
    public void connect() throws IOException {
        connectControl();
    }

    // ---- Video Channel -------------------------------------------------------

    /**
     * Opens the Video Channel socket and performs the VIDEO_HANDSHAKE_REQ pairing with default timeout (2s).
     * Must be called after a successful {@link #connectControl()} + authentication.
     *
     * @param sessionId   the authenticated session ID
     * @param streamToken the one-time token received in the AUTH_RESP payload
     * @throws IOException if the connection or handshake fails
     * @throws SecurityException if the server rejects the stream token
     */
    public void connectVideo(String sessionId, String streamToken) throws IOException {
        connectVideo(sessionId, streamToken, DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /**
     * Opens the Video Channel socket and performs the VIDEO_HANDSHAKE_REQ pairing with explicit timeout.
     *
     * @param sessionId   the authenticated session ID
     * @param streamToken the one-time token received in the AUTH_RESP payload
     * @param timeoutMs   connect timeout in milliseconds
     * @throws IOException if the connection or handshake fails
     * @throws SecurityException if the server rejects the stream token
     */
    public void connectVideo(String sessionId, String streamToken, int timeoutMs) throws IOException {
        System.out.println("[ConnectionManager] Connecting VIDEO channel to "
                + host + ":" + videoPort + " (timeout: " + timeoutMs + "ms)...");
        videoSocket = new Socket();
        videoSocket.connect(new InetSocketAddress(host, videoPort), timeoutMs);
        videoSocket.setTcpNoDelay(true);
        videoSocket.setReceiveBufferSize(512 * 1024);
        videoInputStream = new DataInputStream(videoSocket.getInputStream());

        // Send VIDEO_HANDSHAKE_REQ
        DataOutputStream videoOut = new DataOutputStream(videoSocket.getOutputStream());
        com.remote.common.network.PacketBuilder.writeVideoHandshakeRequest(videoOut, sessionId, streamToken);
        System.out.println("[ConnectionManager] VIDEO_HANDSHAKE_REQ sent for session=" + sessionId);

        // Read VIDEO_HANDSHAKE_RESP
        com.remote.common.network.PacketParser.Packet resp =
                com.remote.common.network.PacketParser.read(videoInputStream);
        if (resp.type() != com.remote.common.network.PacketType.VIDEO_HANDSHAKE_RESP) {
            videoSocket.close();
            throw new IOException("[ConnectionManager] Unexpected response type on video channel: 0x"
                    + Integer.toHexString(resp.type() & 0xFF));
        }
        boolean accepted = resp.payload().length > 0 && resp.payload()[0] == 1;
        if (!accepted) {
            videoSocket.close();
            throw new SecurityException("[ConnectionManager] Server rejected stream token — handshake FAILED.");
        }
        System.out.println("[ConnectionManager] VIDEO channel paired successfully.");
    }

    // ---- Reconnect -----------------------------------------------------------

    /**
     * Reconnects the Control Channel with retry logic.
     *
     * @param maxAttempts số lần thử tối đa (-1 hoặc &lt;= 0 để thử liên tục)
     * @param intervalMs  thời gian nghỉ giữa các lần thử (ms)
     * @return true nếu kết nối lại thành công
     */
    public boolean reconnect(int maxAttempts, long intervalMs) {
        disconnectControl();
        int attempt = 0;

        while (maxAttempts <= 0 || attempt < maxAttempts) {
            attempt++;
            try {
                System.out.println("[ConnectionManager] Đang thử kết nối lại (Lần " + attempt
                        + ") tới " + host + ":" + controlPort + "...");
                connectControl();
                System.out.println("[ConnectionManager] Kết nối lại THÀNH CÔNG tới "
                        + host + ":" + controlPort + "!");
                return true;
            } catch (IOException e) {
                System.err.println("[ConnectionManager] Thử kết nối lại thất bại: " + e.getMessage());
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    // ---- Accessors -----------------------------------------------------------

    /** Control channel output stream — used for sending events, pings, etc. */
    public DataOutputStream getControlOutputStream() { return controlOutputStream; }

    /** Control channel input stream — used for reading PONG, DIR_RESP, FILE_*, CLIPBOARD_SYNC. */
    public DataInputStream getControlInputStream()  { return controlInputStream; }

    /** Video channel input stream — used exclusively for reading FRAME_DATA. */
    public DataInputStream getVideoInputStream()    { return videoInputStream; }

    /** Control channel socket. */
    public Socket getControlSocket()  { return controlSocket; }

    /** Video channel socket. */
    public Socket getVideoSocket()    { return videoSocket; }

    /**
     * @deprecated Use {@link #getControlOutputStream()}.
     */
    @Deprecated
    public DataOutputStream getOutputStream() { return controlOutputStream; }

    /**
     * @deprecated Use {@link #getControlInputStream()}.
     */
    @Deprecated
    public DataInputStream getInputStream() { return controlInputStream; }

    /**
     * @deprecated Use {@link #getControlSocket()}.
     */
    @Deprecated
    public Socket getSocket() { return controlSocket; }

    public String getHost()       { return host; }
    public int    getControlPort() { return controlPort; }
    public int    getVideoPort()   { return videoPort; }

    /** @deprecated Use {@link #getControlPort()}. */
    @Deprecated
    public int getPort() { return controlPort; }

    // ---- State ---------------------------------------------------------------

    public boolean isControlConnected() {
        return controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed();
    }

    public boolean isVideoConnected() {
        return videoSocket != null && videoSocket.isConnected() && !videoSocket.isClosed();
    }

    /** @deprecated Use {@link #isControlConnected()}. */
    @Deprecated
    public boolean isConnected() { return isControlConnected(); }

    // ---- Cleanup -------------------------------------------------------------

    public void disconnectControl() {
        closeQuietly(controlInputStream);
        closeQuietly(controlOutputStream);
        closeQuietly(controlSocket);
        controlInputStream  = null;
        controlOutputStream = null;
        controlSocket       = null;
        System.out.println("[ConnectionManager] Control channel disconnected.");
    }

    public void disconnectVideo() {
        closeQuietly(videoInputStream);
        closeQuietly(videoSocket);
        videoInputStream = null;
        videoSocket      = null;
        System.out.println("[ConnectionManager] Video channel disconnected.");
    }

    public void disconnect() {
        disconnectVideo();
        disconnectControl();
    }

    @Override
    public void close() {
        disconnect();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    // ---- Legacy sendAndReceive (kept for compatibility) ----------------------

    /**
     * Sends a UTF string message to the server on the control channel and awaits a UTF response.
     *
     * @param message Message to send
     * @return Response string from server
     * @throws IOException if network error occurs
     * @deprecated Low-level helper retained for backward compatibility only.
     */
    @Deprecated
    public String sendAndReceive(String message) throws IOException {
        if (!isControlConnected()) {
            throw new IOException("Control socket is not connected.");
        }
        System.out.println("[ConnectionManager] Sending to server: " + message);
        controlOutputStream.writeUTF(message);
        controlOutputStream.flush();
        String response = controlInputStream.readUTF();
        System.out.println("[ConnectionManager] Received from server: " + response);
        return response;
    }
}
