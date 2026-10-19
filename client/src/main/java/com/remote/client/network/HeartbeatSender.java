package com.remote.client.network;

import com.remote.common.network.NetworkConstants;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketType;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically sends PING packets to the Server over the active TCP connection.
 * <p>
 * Ensures thread-safety by synchronizing on the provided
 * {@link DataOutputStream}.
 */
public class HeartbeatSender {

    private final DataOutputStream out;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Constructs a HeartbeatSender bound to the specified socket output stream.
     *
     * @param out the socket DataOutputStream (shared with other senders)
     */
    public HeartbeatSender(DataOutputStream out) {
        this.out = out;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Client-Heartbeat-Thread");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts sending PING packets periodically every
     * {@link NetworkConstants#HEARTBEAT_INTERVAL_MS} ms.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        System.out.println("[HeartbeatSender] Started (Interval: " + NetworkConstants.HEARTBEAT_INTERVAL_MS + " ms)");

        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            try {
                synchronized (out) {
                    PacketBuilder.writeEmpty(out, PacketType.PING);
                }
                System.out.println("[HeartbeatSender] PING sent -> Server");
            } catch (IOException e) {
                System.err
                        .println("[HeartbeatSender] Failed to send PING (connection likely broken): " + e.getMessage());
                stop();
            }
        }, NetworkConstants.HEARTBEAT_INTERVAL_MS, NetworkConstants.HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stops the heartbeat task and shuts down the scheduler thread immediately.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdownNow();
            System.out.println("[HeartbeatSender] Stopped.");
        }
    }

    /**
     * Checks if the heartbeat sender is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }
}
