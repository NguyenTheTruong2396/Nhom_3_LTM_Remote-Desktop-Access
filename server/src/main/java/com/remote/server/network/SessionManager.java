package com.remote.server.network;

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
 * Manages ServerSocket listening, session ID / passcode generation,
 * and dispatching incoming connections to {@link ClientHandler}.
 *
 * Week 2 additions:
 *  - {@link #createSession()} — generates a new (sessionId, passcode) pair.
 *  - {@link #authenticate(String, String)} — validates credentials sent by the client.
 */
public class SessionManager {

    // ---- Networking ----------------------------------------------------------
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService clientThreadPool;

    // ---- Session state -------------------------------------------------------
    /** Maps sessionId → passcode for all active (not-yet-expired) sessions. */
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    private static final int PASSCODE_LENGTH = 6;
    private static final String DIGITS = "0123456789";
    private static final SecureRandom RNG = new SecureRandom();

    // -------------------------------------------------------------------------

    public SessionManager(int port) {
        this.port = port;
        this.clientThreadPool = Executors.newCachedThreadPool();
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

    // ---- TCP Server lifecycle ------------------------------------------------

    /**
     * Starts listening on the configured port and accepts connections.
     */
    public void start() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            System.out.println("[SessionManager] Server đang lắng nghe ở port " + port);

            while (running && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    // Pass 'this' so ClientHandler can call authenticate()
                    clientThreadPool.submit(new ClientHandler(clientSocket, this));
                } catch (IOException e) {
                    if (!running) {
                        System.out.println("[SessionManager] Server socket closed.");
                        break;
                    }
                    System.err.println("[SessionManager] Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[SessionManager] Could not listen on port " + port + ": " + e.getMessage());
        } finally {
            stop();
        }
    }

    /**
     * Stops the server and cleans up resources.
     */
    public void stop() {
        running = false;
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("[SessionManager] Error closing server socket: " + e.getMessage());
            }
        }
        clientThreadPool.shutdown();
        System.out.println("[SessionManager] Server stopped.");
    }

    public boolean isRunning() { return running; }

    public int getPort() { return port; }

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
