package com.remote.server.network;

import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketParser.Packet;
import com.remote.common.network.PacketType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;

/**
 * Phase 5 — Video Channel Handler.
 *
 * Handles one incoming TCP connection on the Video Channel (port 5001).
 *
 * Pairing flow (runs on a pooled thread dispatched by {@link SessionManager}):
 *  1. Read {@link PacketType#VIDEO_HANDSHAKE_REQ} — payload "sessionId|streamToken".
 *  2. Validate the token via {@link SessionManager#validateStreamToken}.
 *  3. If invalid → send {@link PacketType#VIDEO_HANDSHAKE_RESP} (0) and close.
 *  4. If valid   → send {@link PacketType#VIDEO_HANDSHAKE_RESP} (1) and start
 *     {@link VideoStreamSender} which streams JPEG frames at 30 FPS until the
 *     client disconnects or an I/O error occurs.
 *
 * The socket is already tuned for throughput by {@link SessionManager}:
 *   - {@code TCP_NODELAY = true}
 *   - {@code setSendBufferSize(512 * 1024)}
 */
public class VideoChannelHandler implements Runnable {

    private final Socket videoSocket;
    private final SessionManager sessionManager;
    private final String clientAddress;

    public VideoChannelHandler(Socket videoSocket, SessionManager sessionManager) {
        this.videoSocket    = videoSocket;
        this.sessionManager = sessionManager;
        this.clientAddress  = videoSocket.getRemoteSocketAddress().toString();
    }

    @Override
    public void run() {
        System.out.println("[VideoChannelHandler] Video connection from: " + clientAddress);

        VideoStreamSender sender = null;
        try (
            DataInputStream  dis = new DataInputStream(videoSocket.getInputStream());
            DataOutputStream dos = new DataOutputStream(videoSocket.getOutputStream())
        ) {
            // Step 1: Read VIDEO_HANDSHAKE_REQ
            Packet packet = PacketParser.read(dis);
            if (packet.type() != PacketType.VIDEO_HANDSHAKE_REQ) {
                System.err.println("[VideoChannelHandler] Expected VIDEO_HANDSHAKE_REQ but got type=0x"
                        + Integer.toHexString(packet.type() & 0xFF) + " from " + clientAddress);
                PacketBuilder.writeVideoHandshakeResponse(dos, false);
                return;
            }

            // Step 2: Parse "sessionId|streamToken"
            String payload = packet.payloadAsString();
            String[] parts = payload.split("\\|", 2);
            String sessionId  = parts.length > 0 ? parts[0].trim() : "";
            String streamToken = parts.length > 1 ? parts[1].trim() : "";

            // Step 3: Validate token (single-use)
            boolean valid = sessionManager.validateStreamToken(sessionId, streamToken);
            PacketBuilder.writeVideoHandshakeResponse(dos, valid);

            if (!valid) {
                System.err.println("[VideoChannelHandler] Handshake REJECTED for sessionId=" + sessionId
                        + " from " + clientAddress);
                return;
            }

            System.out.println("[VideoChannelHandler] Handshake OK for sessionId=" + sessionId
                    + " — starting VideoStreamSender.");

            // Step 4: Start streaming
            sender = new VideoStreamSender(videoSocket, dos);
            sender.start();

            // Block until the sender thread exits (client disconnects or error)
            // We monitor the socket state; sender runs on its own daemon thread
            try {
                while (sender.isRunning()) {
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (EOFException | SocketException e) {
            System.out.println("[VideoChannelHandler] Video client disconnected: " + clientAddress);
        } catch (IOException e) {
            System.err.println("[VideoChannelHandler] IO error with video client " + clientAddress
                    + ": " + e.getMessage());
        } finally {
            if (sender != null) {
                sender.stop();
            }
            closeSocket();
        }
    }

    private void closeSocket() {
        try {
            if (!videoSocket.isClosed()) {
                videoSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[VideoChannelHandler] Error closing video socket: " + e.getMessage());
        }
        System.out.println("[VideoChannelHandler] Video socket closed for: " + clientAddress);
    }
}
