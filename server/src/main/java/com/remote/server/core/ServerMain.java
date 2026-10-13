package com.remote.server.core;

import com.remote.server.nativeapi.JniBridge;
import com.remote.server.network.SessionManager;

/**
 * Main entry point for the Remote Desktop Server application.
 */
public class ServerMain {

    private static final int DEFAULT_PORT = 8888;

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   Remote Desktop Server - Phase 1");
        System.out.println("=========================================");

        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[ServerMain] Invalid port provided, using default: " + DEFAULT_PORT);
            }
        }

        // Attempt JNI library loading (Option B from architecture)
        System.out.println("[ServerMain] Initializing Native JNI Bridge...");
        boolean jniSuccess = JniBridge.loadNativeLibrary();
        if (jniSuccess) {
            System.out.println("[ServerMain] JNI library initialized successfully.");
        } else {
            System.out.println("[ServerMain] JNI library not loaded (running in pure-Java / network-only mode for Week 1).");
        }

        // Start TCP Server
        System.out.println("[ServerMain] Starting TCP Server on port " + port + "...");
        SessionManager sessionManager = new SessionManager(port);

        // Generate and display the initial session credentials
        SessionManager.SessionInfo sessionInfo = sessionManager.createSession();
        System.out.println("=========================================");
        System.out.println("   Session ID : " + sessionInfo.getSessionId());
        System.out.println("   Passcode   : " + sessionInfo.getPasscode());
        System.out.println("=========================================");

        // Add shutdown hook for graceful exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[ServerMain] Shutting down server...");
            sessionManager.stop();
        }));

        sessionManager.start();
    }
}
