package com.remote.ui;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Điểm khởi chạy chính duy nhất của ứng dụng Remote Desktop Access (HKTNP).
 * Gom toàn bộ giao diện Client và Server về một cửa sổ điều khiển trực quan.
 */
public class MainUI {

    public static void main(String[] args) {
        // Cấu hình khử răng cưa và giao diện hệ thống cho Java Swing
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
