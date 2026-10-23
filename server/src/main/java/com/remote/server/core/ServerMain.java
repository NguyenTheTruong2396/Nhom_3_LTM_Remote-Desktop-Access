package com.remote.server.core;

import com.remote.common.network.NetworkConstants;
import com.remote.server.nativeapi.JniBridge;
import com.remote.server.network.SessionManager;

/**
 * Main entry point for the Remote Desktop Server application.
 */
public class ServerMain {

    // Phase 5: default ports are managed via NetworkConstants (5002/5001).

    public static void main(String[] args) {
        System.out.println("=========================================");
        System.out.println("   Remote Desktop Server - Phase 5");
        System.out.println("   Dual-Socket: Control(:5002) + Video(:5001)");
        System.out.println("=========================================");

        // Phase 5: Control port comes from NetworkConstants; Video port is always 5001.
        int controlPort = NetworkConstants.DEFAULT_CONTROL_PORT;
        if (args.length > 0) {
            try {
                controlPort = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("[ServerMain] Invalid port provided, using default: "
                        + NetworkConstants.DEFAULT_CONTROL_PORT);
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
        System.out.println("[ServerMain] Starting TCP Server — Control:" + controlPort
                + ", Video:" + NetworkConstants.DEFAULT_VIDEO_PORT + "...");
        SessionManager sessionManager = new SessionManager(controlPort);

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
