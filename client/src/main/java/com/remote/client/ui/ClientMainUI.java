package com.remote.client.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point khởi chạy giao diện Client đồ họa.
 * Không thay đổi hay ảnh hưởng đến ClientMain hiện có của dự án.
 */
public class ClientMainUI {
    public static void main(String[] args) {
        try {
            // Sử dụng System Look and Feel để giao diện mượt mà trên Windows
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ClientFrame frame = new ClientFrame();
            frame.setVisible(true);
        });
    }
}
