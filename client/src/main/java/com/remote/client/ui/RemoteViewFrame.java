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

    /**
     * Chạy luồng đọc gói tin FramePacket liên tục từ Server
     */
    public void startReceiving() {
        new Thread(() -> {
            try {
                while (connectionManager != null && connectionManager.isConnected()) {
                    // Đọc gói tin ảnh màn hình từ DataInputStream (sử dụng hàm TLV của Dev 1)
                    FramePacket frame = FramePacket.readFrom(connectionManager.getInputStream());
                    
                    // Cập nhật giao diện
                    updateScreen(frame);
                }
            } catch (IOException e) {
                System.err.println("[RemoteViewFrame] Mất kết nối luồng video: " + e.getMessage());
            }
        }, "Video-Receiver-Thread").start();
    }

    public void updateScreen(FramePacket framePacket) {
        screenPanel.onFrameReceived(framePacket);
    }
}