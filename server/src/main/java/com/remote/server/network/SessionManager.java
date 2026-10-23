package com.remote.server.network;

import com.remote.common.network.NetworkConstants;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 5 — Dual-Socket SessionManager.
 *
 * Manages two independent ServerSockets:
 *   - Control Channel  (port {@value NetworkConstants#DEFAULT_CONTROL_PORT}):
 *     Accepts auth, input events, heartbeat, file transfers, clipboard sync.
 *   - Video Channel    (port {@value NetworkConstants#DEFAULT_VIDEO_PORT}):
 *     Accepts video-only connections after successful pairing via stream token.
 *
 * Pairing flow:
 *  1. Client connects to Control port → AUTH_REQ → if OK, server calls
 *     {@link #generateStreamToken(String)} and embeds the token in AUTH_RESP
 *     payload as "OK|&lt;streamToken&gt;".
 *  2. Client opens a second socket to Video port → VIDEO_HANDSHAKE_REQ
 *     carrying "sessionId|streamToken".
 *  3. {@link VideoChannelHandler} calls {@link #validateStreamToken} to
 *     consume the token (single-use), then starts {@link VideoStreamSender}.
 *
 * Week 2 original API is fully preserved:
 *   {@link #createSession()}, {@link #authenticate(String, String)},
 *   {@link #removeSession(String)}.
 */
public class SessionManager {

    // ---- Networking ----------------------------------------------------------
    private final int controlPort;
    private final int videoPort;
    private ServerSocket controlServerSocket;
    private ServerSocket videoServerSocket;
    private volatile boolean running = false;
    private final ExecutorService clientThreadPool;

    // ---- Session state -------------------------------------------------------
    /** Maps sessionId → passcode for all active (not-yet-expired) sessions. */
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * Stream tokens issued after successful auth.
     * Maps streamToken → TokenEntry (sessionId + expiry timestamp).
     */
    private final Map<String, TokenEntry> pendingTokens = new ConcurrentHashMap<>();

    private static final int PASSCODE_LENGTH = 6;
    private static final String DIGITS = "0123456789";
    private static final SecureRandom RNG = new SecureRandom();

    // ---- Internal token record -----------------------------------------------

    private static final class TokenEntry {
        final String sessionId;
        final long expiresAt;

        TokenEntry(String sessionId) {
            this.sessionId = sessionId;
            this.expiresAt = System.currentTimeMillis() + NetworkConstants.STREAM_TOKEN_TTL_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Creates a SessionManager that listens on both the control and video ports
     * as defined in {@link NetworkConstants}.
     */
    public SessionManager() {
        this(NetworkConstants.DEFAULT_CONTROL_PORT, NetworkConstants.DEFAULT_VIDEO_PORT);
    }

    /**
     * Creates a SessionManager with explicit port numbers.
     *
     * @param controlPort port for the Control Channel
     * @param videoPort   port for the Video Channel
     */
    public SessionManager(int controlPort, int videoPort) {
        this.controlPort = controlPort;
        this.videoPort   = videoPort;
        this.clientThreadPool = Executors.newCachedThreadPool();
    }

    /**
     * Legacy single-port constructor kept for backward compatibility.
     * Maps the given port to the control channel; video channel uses
     * {@link NetworkConstants#DEFAULT_VIDEO_PORT}.
     *
     * @param port control channel port (previously "default port")
     * @deprecated Provide both ports explicitly via {@link #SessionManager(int, int)}.
     */
    @Deprecated
    public SessionManager(int port) {
        this(port, NetworkConstants.DEFAULT_VIDEO_PORT);
    }

    // ---- Session management --------------------------------------------------

    /**
     * Creates a new session with a random 6-digit passcode.
     *
     * @return {@link SessionInfo} containing the newly generated sessionId and passcode.
     */
    public SessionInfo createSession() {
        String sessionId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String passcode  = generatePasscode();
        activeSessions.put(sessionId, passcode);
        System.out.println("[SessionManager] New session created — ID: " + sessionId
                + "  Passcode: " + passcode);
        return new SessionInfo(sessionId, passcode);
    }

    /**
     * Validates a (sessionId, passcode) pair received from a client AUTH_REQ.
     *
     * @param sessionId session ID sent by client
     * @param passcode  passcode sent by client
     * @return {@code true} if the pair matches an active session
     */
    public boolean authenticate(String sessionId, String passcode) {
        String expected = activeSessions.get(sessionId);
        return expected != null && expected.equals(passcode);
    }

    /**
     * Removes an active session (call after successful connection or on timeout).
     */
    public void removeSession(String sessionId) {
        activeSessions.remove(sessionId);
        System.out.println("[SessionManager] Session removed: " + sessionId);
    }

    // ---- Stream token management ---------------------------------------------

    /**
     * Generates a one-time stream token for the given session and registers it
     * with a 30-second TTL.  The token is included in the AUTH_RESP payload
     * so the client can present it when opening the Video Channel.
     *
     * @param sessionId the authenticated session ID
     * @return a fresh, URL-safe random stream token string
     */
    public String generateStreamToken(String sessionId) {
        // Purge any stale tokens first
        pendingTokens.entrySet().removeIf(e -> e.getValue().isExpired());

        String token = UUID.randomUUID().toString().replace("-", "");
        pendingTokens.put(token, new TokenEntry(sessionId));
        System.out.println("[SessionManager] Stream token issued for session " + sessionId
                + " — token=" + token.substring(0, 8) + "...");
        return token;
    }

    /**
     * Validates and consumes a stream token.
     * The token is single-use: it is removed from the pending map whether
     * validation succeeds or fails.
     *
     * @param sessionId   session ID presented in the VIDEO_HANDSHAKE_REQ
     * @param streamToken the token to validate
     * @return {@code true} if the token is valid, not expired, and matches the session
     */
    public boolean validateStreamToken(String sessionId, String streamToken) {
        TokenEntry entry = pendingTokens.remove(streamToken);
        if (entry == null) {
            System.err.println("[SessionManager] Stream token not found: " + streamToken);
            return false;
        }
        if (entry.isExpired()) {
            System.err.println("[SessionManager] Stream token EXPIRED for session: " + sessionId);
            return false;
        }
        if (!entry.sessionId.equals(sessionId)) {
            System.err.println("[SessionManager] Stream token session mismatch: expected="
                    + entry.sessionId + " got=" + sessionId);
            return false;
        }
        System.out.println("[SessionManager] Stream token validated for session: " + sessionId);
        return true;
    }

    // ---- TCP Server lifecycle ------------------------------------------------

    /**
     * Starts listening on both the Control and Video ports concurrently.
     * This method blocks on the control accept loop.
     */
    public void start() {
        try {
            controlServerSocket = new ServerSocket(controlPort);
            videoServerSocket   = new ServerSocket(videoPort);
            running = true;

            System.out.println("[SessionManager] Control Channel lắng nghe port " + controlPort);
            System.out.println("[SessionManager] Video  Channel lắng nghe port " + videoPort);

            // Video accept loop runs on its own daemon thread
            Thread videoAcceptThread = new Thread(this::runVideoAcceptLoop, "Video-Accept-Thread");
            videoAcceptThread.setDaemon(true);
            videoAcceptThread.start();

            // Control accept loop runs on the calling thread (blocking)
            runControlAcceptLoop();

        } catch (IOException e) {
            System.err.println("[SessionManager] Could not start server: " + e.getMessage());
        } finally {
            stop();
        }
    }

    private void runControlAcceptLoop() {
        while (running && !controlServerSocket.isClosed()) {
            try {
                Socket clientSocket = controlServerSocket.accept();
                // Disable Nagle on control socket for minimal latency
                clientSocket.setTcpNoDelay(true);
                clientThreadPool.submit(new ClientHandler(clientSocket, this));
            } catch (IOException e) {
                if (!running) {
                    System.out.println("[SessionManager] Control socket closed.");
                    break;
                }
                System.err.println("[SessionManager] Error accepting control connection: " + e.getMessage());
            }
        }
    }

    private void runVideoAcceptLoop() {
        while (running && !videoServerSocket.isClosed()) {
            try {
                Socket videoSocket = videoServerSocket.accept();
                // Configure for high-throughput video: no Nagle, large send buffer
                videoSocket.setTcpNoDelay(true);
                videoSocket.setSendBufferSize(512 * 1024);
                clientThreadPool.submit(new VideoChannelHandler(videoSocket, this));
            } catch (IOException e) {
                if (!running) {
                    System.out.println("[SessionManager] Video socket closed.");
                    break;
                }
                System.err.println("[SessionManager] Error accepting video connection: " + e.getMessage());
            }
        }
    }

    /**
     * Stops both server sockets and cleans up resources.
     */
    public void stop() {
        running = false;
        closeSilently(controlServerSocket);
        closeSilently(videoServerSocket);
        clientThreadPool.shutdown();
        System.out.println("[SessionManager] Server stopped.");
    }

    private static void closeSilently(ServerSocket ss) {
        if (ss != null && !ss.isClosed()) {
            try { ss.close(); } catch (IOException ignored) {}
        }
    }

    public boolean isRunning() { return running; }

    public int getControlPort() { return controlPort; }

    public int getVideoPort()   { return videoPort; }

    /** @deprecated Use {@link #getControlPort()}. */
    @Deprecated
    public int getPort() { return controlPort; }

    // ---- Helpers -------------------------------------------------------------

    private static String generatePasscode() {
        StringBuilder sb = new StringBuilder(PASSCODE_LENGTH);
        for (int i = 0; i < PASSCODE_LENGTH; i++) {
            sb.append(DIGITS.charAt(RNG.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }

    // ---- Value object --------------------------------------------------------

    /**
     * Immutable pair of (sessionId, passcode) returned by {@link #createSession()}.
     */
    public static final class SessionInfo {
        private final String sessionId;
        private final String passcode;

        SessionInfo(String sessionId, String passcode) {
            this.sessionId = sessionId;
            this.passcode  = passcode;
        }

        public String getSessionId() { return sessionId; }
        public String getPasscode()  { return passcode; }

        @Override
        public String toString() {
            return "SessionInfo{id=" + sessionId + ", passcode=" + passcode + "}";
        }
    }
}
