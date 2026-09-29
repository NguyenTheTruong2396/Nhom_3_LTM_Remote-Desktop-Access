package com.remote.client.core;

import com.remote.client.network.ConnectionManager;

/**
 * Main entry point for the Remote Desktop Client application.
 */
public class ClientMain {

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   Remote Desktop Client - Phase 1");
        System.out.println("=========================================");

        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;

        if (args.length > 0) {
            host = args[0];
        }
        if (args.length > 1) {
            try {
                port = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.out.println("[ClientMain] Invalid port provided, using default: " + DEFAULT_PORT);
            }
        }

        System.out.println("[ClientMain] Target Server: " + host + ":" + port);

        try (ConnectionManager connection = new ConnectionManager(host, port)) {
            connection.connect();

            // Send smoke test message required by Phase 1
            String testMessage = "Hello from Client";
            String reply = connection.sendAndReceive(testMessage);

            System.out.println("[ClientMain] Smoke test exchange succeeded!");
            System.out.println("[ClientMain] Server replied: " + reply);
        } catch (Exception e) {
            System.err.println("[ClientMain] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
