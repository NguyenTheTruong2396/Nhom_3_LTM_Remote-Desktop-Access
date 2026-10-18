package com.remote.client.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Manages TCP connection from Client to Server.
 */
public class ConnectionManager implements AutoCloseable {

    private final String host;
    private final int port;
    private Socket socket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;

    public ConnectionManager(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Establishes TCP connection to the server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        System.out.println("[ConnectionManager] Connecting to server at " + host + ":" + port + "...");
        this.socket = new Socket(host, port);
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.outputStream = new DataOutputStream(socket.getOutputStream());
        System.out.println("[ConnectionManager] Successfully connected to server at " + host + ":" + port);
    }

    /**
     * Thử kết nối lại với Server theo chu kỳ lặp lại sau mỗi intervalMs.
     *
     * @param maxAttempts số lần thử tối đa (-1 hoặc <= 0 để thử liên tục không giới hạn)
     * @param intervalMs thời gian nghỉ giữa các lần thử (ms)
     * @return true nếu kết nối lại thành công, false nếu đạt maxAttempts mà thất bại
     */
    public boolean reconnect(int maxAttempts, long intervalMs) {
        disconnect(); // Đóng socket và stream cũ trước khi thử lại
        int attempt = 0;

        while (maxAttempts <= 0 || attempt < maxAttempts) {
            attempt++;
            try {
                System.out.println("[ConnectionManager] Đang thử kết nối lại (Lần " + attempt + ") tới " + host + ":" + port + "...");
                this.socket = new Socket(host, port);
                this.inputStream = new DataInputStream(socket.getInputStream());
                this.outputStream = new DataOutputStream(socket.getOutputStream());
                System.out.println("[ConnectionManager] Kết nối lại THÀNH CÔNG tới " + host + ":" + port + "!");
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

    /**
     * Sends a UTF string message to the server and awaits a UTF string response.
     *
     * @param message Message to send
     * @return Response string from server
     * @throws IOException if network error occurs
     */
    public String sendAndReceive(String message) throws IOException {
        if (!isConnected()) {
            throw new IOException("Socket is not connected.");
        }

        System.out.println("[ConnectionManager] Sending to server: " + message);
        outputStream.writeUTF(message);
        outputStream.flush();

        String response = inputStream.readUTF();
        System.out.println("[ConnectionManager] Received from server: " + response);
        return response;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        disconnect();
    }

    public DataOutputStream getOutputStream() {
        return outputStream;
    }

    public DataInputStream getInputStream() {
        return inputStream;
    }

    public Socket getSocket() {
        return socket;
    }

    public void disconnect() {
        try {
            if (inputStream != null) inputStream.close();
            if (outputStream != null) outputStream.close();
            if (socket != null && !socket.isClosed()) socket.close();
            System.out.println("[ConnectionManager] Disconnected from server.");
        } catch (IOException e) {
            System.err.println("[ConnectionManager] Error while disconnecting: " + e.getMessage());
        }
    }
}
