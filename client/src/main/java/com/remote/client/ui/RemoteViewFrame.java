package com.remote.client.ui;

import com.remote.client.network.ConnectionManager;
import com.remote.client.network.EventSender;
import com.remote.client.network.FileSender;
import com.remote.common.model.FramePacket;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
        // >>> Thanh công cụ điều khiển cao cấp (Toolbar) <<<
        // =========================================================================
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBackground(new Color(245, 247, 250));
        toolBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 224, 230)),
                new EmptyBorder(6, 12, 6, 12)
        ));

        // 1. NÚT TRUYỀN FILE TO VÀ RÕ RÀNG
        JButton btnSendFile = new JButton("📁 Truyền File Sang Máy Host");
        btnSendFile.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnSendFile.setForeground(Color.WHITE);
        btnSendFile.setBackground(new Color(0x1B, 0x75, 0xBC));
        btnSendFile.setFocusPainted(false);
        btnSendFile.setBorder(new EmptyBorder(8, 18, 8, 18));
        btnSendFile.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnSendFile.addActionListener(e -> {
            if (connectionManager == null || connectionManager.getOutputStream() == null) {
                JOptionPane.showMessageDialog(this, "Chưa kết nối tới Server!", "Lỗi", JOptionPane.ERROR_MESSAGE);
                return;
            }

            JFileChooser fileChooser = new JFileChooser();
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File selectedFile = fileChooser.getSelectedFile();

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

        // 2. NÚT NGẮT KẾT NỐI (DISCONNECT) NỔI BẬT
        JButton btnDisconnect = new JButton("🔴 Ngắt Kết Nối");
        btnDisconnect.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btnDisconnect.setForeground(Color.WHITE);
        btnDisconnect.setBackground(new Color(0xD9, 0x38, 0x3A));
        btnDisconnect.setFocusPainted(false);
        btnDisconnect.setBorder(new EmptyBorder(8, 18, 8, 18));
        btnDisconnect.setCursor(new Cursor(Cursor.HAND_CURSOR));

        btnDisconnect.addActionListener(e -> {
            int opt = JOptionPane.showConfirmDialog(this, 
                    "Bạn có chắc chắn muốn ngắt kết nối phiên điều khiển này?", 
                    "Xác nhận ngắt kết nối", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (opt == JOptionPane.YES_OPTION) {
                disconnectAndExit();
            }
        });

        toolBar.add(btnSendFile);
        toolBar.add(Box.createHorizontalStrut(14));
        toolBar.add(btnDisconnect);
        toolBar.add(Box.createHorizontalGlue());

        add(toolBar, BorderLayout.NORTH);

        // Đảm bảo ngắt kết nối khi người dùng bấm dấu X tắt cửa sổ
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                disconnectAndExit();
            }
        });
    }

    private void disconnectAndExit() {
        try {
            if (connectionManager != null && connectionManager.getOutputStream() != null) {
                com.remote.common.network.PacketBuilder.writeEmpty(connectionManager.getOutputStream(), com.remote.common.network.PacketType.DISCONNECT);
            }
        } catch (Exception ignored) {}
        if (connectionManager != null) {
            connectionManager.close();
        }
        dispose();
        // Mở lại cửa sổ đăng nhập Client
        SwingUtilities.invokeLater(() -> {
            ClientFrame frame = new ClientFrame();
            frame.setVisible(true);
        });
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