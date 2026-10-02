package com.remote.server.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

/**
 * Handles incoming TCP connection from a single client.
 * In Week 1, handles basic message echo/exchange to verify connection.
 */
public class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final String clientAddress;

    public ClientHandler(Socket socket) {
        this.clientSocket = socket;
        this.clientAddress = socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void run() {
        System.out.println("[ClientHandler] Client connected from: " + clientAddress);

        try (
            DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
            DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())
        ) {
            while (!clientSocket.isClosed()) {
                try {
                    String message = dis.readUTF();
                    System.out.println("[ClientHandler] Received message from [" + clientAddress + "]: " + message);

                    String response = "Echo from Server: " + message;
                    dos.writeUTF(response);
                    dos.flush();
                    System.out.println("[ClientHandler] Sent response to [" + clientAddress + "]: " + response);
                } catch (EOFException | SocketException e) {
                    System.out.println("[ClientHandler] Client disconnected: " + clientAddress);
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[ClientHandler] IO Error with client " + clientAddress + ": " + e.getMessage());
        } finally {
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                System.err.println("[ClientHandler] Error closing socket: " + e.getMessage());
            }
            System.out.println("[ClientHandler] Connection closed for: " + clientAddress);
        }
    }
}
