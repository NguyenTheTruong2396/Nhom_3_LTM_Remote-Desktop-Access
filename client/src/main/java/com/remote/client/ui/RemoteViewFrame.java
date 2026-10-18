package com.remote.client.ui;

import com.remote.client.network.ConnectionManager;
import com.remote.client.network.EventSender;
import com.remote.client.network.FileSender;
import com.remote.common.model.FramePacket;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class RemoteViewFrame extends JFrame {
    private final ScreenPanel screenPanel;
    private final EventSender eventSender;
    private final ConnectionManager connectionManager;

    public RemoteViewFrame(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;

        setTitle("Remote Desktop - Control Session");
        setSize(1280, 720);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        screenPanel = new ScreenPanel();
        add(screenPanel, BorderLayout.CENTER);

        if (connectionManager != null && connectionManager.getOutputStream() != null) {
            this.eventSender = new EventSender(connectionManager.getOutputStream());
        } else {
            this.eventSender = null;
        }

        // Truyền eventSender sang cho ScreenPanel
        this.screenPanel.setEventSender(this.eventSender);

        // =========================================================================
        // >>> ĐOẠN CẬP NHẬT MỚI: Thêm Thanh công cụ (JToolBar) để Gửi File <<<
        // =========================================================================
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false); // Cố định thanh công cụ ở mép trên

        JButton btnSendFile = new JButton("📁 Gửi File sang Máy Host");
        btnSendFile.addActionListener(e -> {
            if (connectionManager == null || connectionManager.getOutputStream() == null) {
                JOptionPane.showMessageDialog(this, "Chưa kết nối tới Server!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            JFileChooser fileChooser = new JFileChooser();
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();

                // Gọi FileSender gửi file qua DataOutputStream của Socket
                FileSender.sendFile(selectedFile, connectionManager.getOutputStream(), new FileSender.FileProgressCallback() {
                    @Override
                    public void onProgress(int percentage, long bytesSent, long totalBytes) {
                        System.out.printf("[File Transfer] Đang gửi %s: %d%% (%d/%d bytes)\n", 
                                selectedFile.getName(), percentage, bytesSent, totalBytes);
                    }

                    @Override
                    public void onComplete() {
                        JOptionPane.showMessageDialog(RemoteViewFrame.this, 
                                "Đã gửi file thành công sang máy Host:\n" + selectedFile.getName());
                    }

                    @Override
                    public void onError(Exception ex) {
                        JOptionPane.showMessageDialog(RemoteViewFrame.this, 
                                "Lỗi khi gửi file: " + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        });

        toolBar.add(btnSendFile);
        add(toolBar, BorderLayout.NORTH); // Đặt thanh công cụ ở mép trên cửa sổ
    }

    private Runnable disconnectCallback;

    public void setDisconnectCallback(Runnable disconnectCallback) {
        this.disconnectCallback = disconnectCallback;
    }

    /**
     * Cập nhật connection mới sau khi reconnect thành công.
     */
    public void updateConnection(ConnectionManager newConnection) {
        if (newConnection != null && newConnection.getOutputStream() != null) {
            this.screenPanel.setEventSender(new EventSender(newConnection.getOutputStream()));
        }
    }

    /**
     * Chạy luồng đọc gói tin FramePacket liên tục từ Server
     */
    public void startReceiving() {
        new Thread(() -> {
            try {
                java.io.DataInputStream in = connectionManager.getInputStream();
                while (connectionManager != null && connectionManager.isConnected()) {
                    com.remote.common.network.PacketParser.Packet packet = com.remote.common.network.PacketParser.read(in);
                    
                    if (packet.type() == com.remote.common.network.PacketType.FRAME_DATA) {
                        byte[] payload = packet.payload();
                        if (payload.length >= 8) {
                            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(payload);
                            long timestamp = bb.getLong();
                            byte[] imgData = new byte[payload.length - 8];
                            bb.get(imgData);
                            FramePacket frame = new FramePacket(timestamp, imgData);
                            updateScreen(frame);
                        }
                    } else if (packet.type() == com.remote.common.network.PacketType.PONG) {
                        // Nhận gói phản hồi Keep-alive PONG từ Server, bỏ qua không xử lý
                    } else if (packet.type() == com.remote.common.network.PacketType.DISCONNECT) {
                        System.out.println("[RemoteViewFrame] Server đã ngắt kết nối.");
                        break;
                    }
                }
            } catch (IOException e) {
                System.err.println("[RemoteViewFrame] Mất kết nối luồng video: " + e.getMessage());
                if (disconnectCallback != null) {
                    disconnectCallback.run();
                }
            }
        }, "Video-Receiver-Thread").start();
    }

    public void updateScreen(FramePacket framePacket) {
        screenPanel.onFrameReceived(framePacket);
    }
}