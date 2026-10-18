package com.remote.server.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point khởi chạy giao diện Server đồ họa.
 * Không thay đổi hay ảnh hưởng đến ServerMain hiện có của dự án.
 */
public class ServerMainUI {
    public static void main(String[] args) {
        try {
            // Sử dụng System Look and Feel để giao diện mượt mà trên Windows
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            ServerFrame frame = new ServerFrame();
            frame.setVisible(true);
        });
    }
}
