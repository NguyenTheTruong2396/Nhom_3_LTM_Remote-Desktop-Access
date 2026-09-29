package com.remote.server.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages ServerSocket listening and dispatches incoming connections to ClientHandler.
 */
public class SessionManager {

    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private final ExecutorService clientThreadPool;

    public SessionManager(int port) {
        this.port = port;
        this.clientThreadPool = Executors.newCachedThreadPool();
    }

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
                    clientThreadPool.submit(new ClientHandler(clientSocket));
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

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }
}
