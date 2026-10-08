package com.remote.client.network;

import com.remote.common.model.InputEventPacket;
import java.io.ObjectOutputStream;

public class event_sender {
    private ObjectOutputStream out;

    public event_sender(ObjectOutputStream out) {
        this.out = out;
    }

    public synchronized void send(InputEventPacket packet) {
        try {
            out.writeObject(packet);
            out.flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}