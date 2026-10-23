package com.remote.client.ui;

import com.remote.client.network.ConnectionManager;
import com.remote.client.network.EventSender;
import com.remote.client.network.FileSender;
import com.remote.client.network.FileTransferService;
import com.remote.common.model.FramePacket;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketType;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Cửa sổ điều khiển máy tính từ xa cao cấp (HKTNP Remote Viewer):
 * - Loại bỏ hoàn toàn emoji unicode gây lỗi hiển thị ký tự ô vuông [ ] trên Windows Swing.
 * - Tích hợp icon vector Java2D vẽ trực tiếp sắc nét (Signal Ping, Speed FPS, File, Disconnect).
 * - Hiển thị Ping (ms) và FPS thời gian thực với màu sắc cảnh báo thông minh.
 * - Tinh chỉnh kích thước nút bấm gọn gàng, hiện đại.
 */
public class RemoteViewFrame extends JFrame {

    private final ScreenPanel screenPanel;
    private final EventSender eventSender;
    private final ConnectionManager connectionManager;

    // Badges đo lường hiệu năng
    private MetricBadge lblPingBadge;
    private MetricBadge lblFpsBadge;
    private JLabel lblStatusBadge;

    // Thống kê Ping & FPS
    private volatile long lastPingSentTime = 0;
    private volatile int currentPingMs = 0;
    private volatile int currentFps = 0;
    private int frameCount = 0;
    private long lastFpsCalcTime = System.currentTimeMillis();
    private Timer pingTimer;

    // Callbacks
    private Runnable onDisconnectCallback;
    private Runnable disconnectCallback;

    // Quản lý truyền file & clipboard sync
    private FileTransferService fileTransferService;
    private FileTransferDialog fileTransferDialog;
    private volatile boolean isSettingClientClipboard = false;
    private Thread clientClipboardMonitorThread;

    public RemoteViewFrame(ConnectionManager connectionManager) {
        this(connectionManager, null);
    }

    public RemoteViewFrame(ConnectionManager connectionManager, Runnable onDisconnectCallback) {
        this.connectionManager = connectionManager;
        this.onDisconnectCallback = onDisconnectCallback;

        String serverAddr = (connectionManager != null && connectionManager.getHost() != null)
                ? connectionManager.getHost() + ":" + connectionManager.getPort()
                : "Host";

        setTitle("HKTNP Remote Desktop - Phiên điều khiển [" + serverAddr + "]");
        setSize(1280, 760);
        setMinimumSize(new Dimension(850, 520));
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        // 1. Screen viewport
        screenPanel = new ScreenPanel();
        add(screenPanel, BorderLayout.CENTER);

        if (connectionManager != null && connectionManager.getOutputStream() != null) {
            this.eventSender = new EventSender(connectionManager.getOutputStream());
            this.fileTransferService = new FileTransferService(connectionManager.getOutputStream());
            startClientClipboardMonitor();
        } else {
            this.eventSender = null;
            this.fileTransferService = null;
        }
        this.screenPanel.setEventSender(this.eventSender);

        // 2. Thanh công cụ Toolbar chuyên nghiệp (Hoàn toàn không dùng emoji để tránh lỗi icon ô vuông)
        JPanel topBar = createModernToolbar(serverAddr);
        add(topBar, BorderLayout.NORTH);

        // 3. Khởi động gửi Ping định kỳ đo RTT
        startPingScheduler();

        // 4. Lắng nghe đóng cửa sổ
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndDisconnect();
            }
        });
    }

    private JPanel createModernToolbar(String serverAddr) {
        JPanel bar = new JPanel(new BorderLayout(15, 0));
        bar.setBackground(new Color(24, 28, 36)); // Dark sleek theme
        bar.setPreferredSize(new Dimension(getWidth(), 48));
        bar.setBorder(new EmptyBorder(6, 16, 6, 16));

        // --- CỤM BÊN TRÁI: Trạng thái kết nối, Ping và FPS (Vẽ icon vector độc quyền, không dùng emoji) ---
        JPanel leftCluster = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        leftCluster.setOpaque(false);

        // Status Indicator với chấm tròn vẽ vector
        lblStatusBadge = new JLabel("Đã kết nối (" + serverAddr + ")") {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(34, 197, 94)); // Green
                g2.fillOval(2, (getHeight() - 8) / 2, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        lblStatusBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblStatusBadge.setForeground(new Color(220, 225, 235));
        lblStatusBadge.setBorder(new EmptyBorder(0, 14, 0, 0));
        leftCluster.add(lblStatusBadge);

        // Ping Badge (Icon cột sóng vector)
        lblPingBadge = new MetricBadge(MetricType.PING, "Ping: -- ms", new Color(34, 197, 94));
        leftCluster.add(lblPingBadge);

        // FPS Badge (Icon tốc độ vector)
        lblFpsBadge = new MetricBadge(MetricType.FPS, "0 FPS", new Color(56, 189, 248));
        leftCluster.add(lblFpsBadge);

        bar.add(leftCluster, BorderLayout.WEST);

        // --- CỤM BÊN PHẢI: Các nút chức năng (Đồng bộ Clipboard, Quản lý File 2 chiều, Dừng kết nối) ---
        JPanel rightCluster = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        rightCluster.setOpaque(false);

        // Nút Đồng bộ Clipboard: Kích thước 150x32
        JButton btnClipboard = createVectorActionButton(
                ButtonIconType.CLIPBOARD,
                "Đồng bộ Clipboard",
                new Color(0x0D, 0x94, 0x88),
                new Color(0x14, 0xB8, 0xA6)
        );
        btnClipboard.setPreferredSize(new Dimension(150, 32));
        btnClipboard.addActionListener(e -> sendClientClipboardNow());
        rightCluster.add(btnClipboard);

        // Nút Quản lý File (Upload & Download 2 chiều): Kích thước 135x32
        JButton btnSendFile = createVectorActionButton(
                ButtonIconType.FILE_TRANSFER,
                "Quản lý File",
                new Color(0x1B, 0x75, 0xBC),
                new Color(0x28, 0x8C, 0xDB)
        );
        btnSendFile.setPreferredSize(new Dimension(135, 32));
        btnSendFile.addActionListener(e -> openFileTransferManager());
        rightCluster.add(btnSendFile);

        // Nút Dừng kết nối: Kích thước 120x32
        JButton btnDisconnect = createVectorActionButton(
                ButtonIconType.DISCONNECT,
                "Dừng kết nối",
                new Color(0xDC, 0x26, 0x26),
                new Color(0xEF, 0x44, 0x44)
        );
        btnDisconnect.setPreferredSize(new Dimension(120, 32));
        btnDisconnect.addActionListener(e -> confirmAndDisconnect());
        rightCluster.add(btnDisconnect);

        bar.add(rightCluster, BorderLayout.EAST);

        return bar;
    }

    private enum MetricType { PING, FPS }
    private enum ButtonIconType { FILE_TRANSFER, CLIPBOARD, DISCONNECT }

    private static class MetricBadge extends JLabel {
        private final MetricType type;
        private Color accentColor;

        public MetricBadge(MetricType type, String text, Color accentColor) {
            super(text);
            this.type = type;
            this.accentColor = accentColor;
            setFont(new Font("Segoe UI", Font.BOLD, 12));
            setForeground(accentColor);
            setBorder(new EmptyBorder(3, 24, 3, 10)); // Khoảng cách bên trái để vẽ icon
            setOpaque(false);
        }

        public void setAccentColor(Color color) {
            this.accentColor = color;
            setForeground(color);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // Nền badge mờ
            g2.setColor(new Color(255, 255, 255, 18));
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));

            // Viền bo badge
            g2.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 60));
            g2.setStroke(new BasicStroke(1.0f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, 12, 12));

            // Vẽ Icon Vector sắc nét
            int iconX = 8;
            int iconY = (h - 12) / 2;

            g2.setColor(accentColor);
            if (type == MetricType.PING) {
                // Vẽ 3 cột sóng mạng tăng dần độ cao
                g2.fillRect(iconX, iconY + 8, 2, 4);
                g2.fillRect(iconX + 4, iconY + 4, 2, 8);
                g2.fillRect(iconX + 8, iconY, 2, 12);
            } else {
                // Vẽ tia chớp / tốc độ FPS
                Path2D.Float p = new Path2D.Float();
                p.moveTo(iconX + 6, iconY);
                p.lineTo(iconX + 2, iconY + 6);
                p.lineTo(iconX + 5, iconY + 6);
                p.lineTo(iconX + 4, iconY + 12);
                p.lineTo(iconX + 9, iconY + 5);
                p.lineTo(iconX + 6, iconY + 5);
                p.closePath();
                g2.fill(p);
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }

    private JButton createVectorActionButton(ButtonIconType iconType, String text, Color baseColor, Color hoverColor) {
        JButton btn = new JButton(text) {
            private boolean isHover = false;
            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mouseEntered(java.awt.event.MouseEvent e) { isHover = true; repaint(); }
                    @Override public void mouseExited(java.awt.event.MouseEvent e) { isHover = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = 8;

                g2.setColor(isHover ? hoverColor : baseColor);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textW = fm.stringWidth(getText());
                int totalW = textW + 18; // 12px icon + 6px gap
                int startX = (w - totalW) / 2;
                int iconY = (h - 12) / 2;

                // Vẽ icon vector màu trắng
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.4f));

                if (iconType == ButtonIconType.FILE_TRANSFER) {
                    // Vẽ biểu tượng tờ file / tài liệu
                    g2.drawRoundRect(startX, iconY, 10, 12, 2, 2);
                    g2.drawLine(startX + 3, iconY + 4, startX + 7, iconY + 4);
                    g2.drawLine(startX + 3, iconY + 7, startX + 7, iconY + 7);
                } else if (iconType == ButtonIconType.CLIPBOARD) {
                    // Vẽ biểu tượng kẹp Clipboard
                    g2.drawRoundRect(startX, iconY + 2, 10, 10, 2, 2);
                    g2.fillRect(startX + 3, iconY, 4, 3);
                    g2.drawLine(startX + 2, iconY + 5, startX + 8, iconY + 5);
                    g2.drawLine(startX + 2, iconY + 8, startX + 8, iconY + 8);
                } else {
                    // Vẽ biểu tượng dừng / ngắt kết nối (hình vuông stop)
                    g2.fillRoundRect(startX, iconY + 1, 10, 10, 2, 2);
                }

                // Vẽ text
                int textX = startX + 16;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };

        btn.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btn.setPreferredSize(new Dimension(130, 32));
        btn.setMaximumSize(new Dimension(140, 32));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void openFileTransferManager() {
        if (fileTransferService == null && connectionManager != null && connectionManager.getOutputStream() != null) {
            fileTransferService = new FileTransferService(connectionManager.getOutputStream());
        }
        if (fileTransferDialog == null || !fileTransferDialog.isDisplayable()) {
            fileTransferDialog = new FileTransferDialog(this, fileTransferService);
        }
        fileTransferDialog.setVisible(true);
        fileTransferDialog.toFront();
    }

    private void handleSendFile() {
        openFileTransferManager();
    }

    private void sendClientClipboardNow() {
        try {
            Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clip.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                String text = (String) clip.getData(DataFlavor.stringFlavor);
                if (text != null && !text.isEmpty() && connectionManager != null && connectionManager.getOutputStream() != null) {
                    synchronized (connectionManager.getOutputStream()) {
                        PacketBuilder.write(connectionManager.getOutputStream(), PacketType.CLIPBOARD_SYNC,
                                text.getBytes(StandardCharsets.UTF_8));
                    }
                    String preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
                    JOptionPane.showMessageDialog(this, "Đã gửi nội dung Clipboard sang Server:\n" + preview,
                            "Đồng bộ Clipboard thành công", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, "Clipboard máy bạn hiện đang trống!", "Thông báo", JOptionPane.WARNING_MESSAGE);
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Lỗi đọc Clipboard: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setClientClipboardText(String text) {
        if (text == null) return;
        try {
            isSettingClientClipboard = true;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            System.out.println("[RemoteViewFrame] Đã tự động nhận Clipboard từ Server.");
        } catch (Exception ignored) {
        } finally {
            try { Thread.sleep(150); } catch (Exception ignored) {}
            isSettingClientClipboard = false;
        }
    }

    private void startClientClipboardMonitor() {
        clientClipboardMonitorThread = new Thread(() -> {
            String lastText = "";
            while (connectionManager != null && connectionManager.isConnected()) {
                try {
                    Thread.sleep(800);
                    if (!isSettingClientClipboard && connectionManager.getOutputStream() != null) {
                        Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
                        if (clip.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                            String current = (String) clip.getData(DataFlavor.stringFlavor);
                            if (current != null && !current.isEmpty() && !current.equals(lastText)) {
                                lastText = current;
                                synchronized (connectionManager.getOutputStream()) {
                                    PacketBuilder.write(connectionManager.getOutputStream(), PacketType.CLIPBOARD_SYNC,
                                            current.getBytes(StandardCharsets.UTF_8));
                                }
                                System.out.println("[RemoteViewFrame] Đã tự động đẩy Clipboard mới sang Server.");
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }, "Client-Clipboard-Monitor");
        clientClipboardMonitorThread.setDaemon(true);
        clientClipboardMonitorThread.start();
    }

    private void confirmAndDisconnect() {
        int opt = JOptionPane.showConfirmDialog(this,
                "Bạn có chắc chắn muốn dừng kết nối phiên điều khiển này?",
                "Xác nhận dừng kết nối", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (opt == JOptionPane.YES_OPTION) {
            disconnectAndExit();
        }
    }

    private void disconnectAndExit() {
        if (pingTimer != null) {
            pingTimer.stop();
        }

        if (fileTransferDialog != null) {
            fileTransferDialog.dispose();
            fileTransferDialog = null;
        }

        try {
            if (connectionManager != null && connectionManager.getOutputStream() != null) {
                PacketBuilder.writeEmpty(connectionManager.getOutputStream(), PacketType.DISCONNECT);
            }
        } catch (Exception ignored) {}

        if (connectionManager != null) {
            connectionManager.close();
        }

        dispose();

        if (onDisconnectCallback != null) {
            SwingUtilities.invokeLater(onDisconnectCallback);
        } else {
            try {
                Class<?> uiMainClass = Class.forName("com.remote.ui.MainUI");
                uiMainClass.getMethod("main", String[].class).invoke(null, (Object) new String[]{});
            } catch (Exception ignored) {}
        }
    }

    private void startPingScheduler() {
        pingTimer = new Timer(1500, e -> {
            if (connectionManager != null && connectionManager.isConnected()) {
                try {
                    long now = System.currentTimeMillis();
                    lastPingSentTime = now;
                    ByteBuffer bb = ByteBuffer.allocate(8);
                    bb.putLong(now);
                    synchronized (connectionManager.getOutputStream()) {
                        PacketBuilder.write(connectionManager.getOutputStream(), PacketType.PING, bb.array());
                    }
                } catch (Exception ignored) {}
            }
        });
        pingTimer.start();
    }

    private void onPongReceived(byte[] payload) {
        long now = System.currentTimeMillis();
        long rtt;
        if (payload != null && payload.length >= 8) {
            long sent = ByteBuffer.wrap(payload).getLong();
            rtt = Math.max(1, now - sent);
        } else if (lastPingSentTime > 0) {
            rtt = Math.max(1, now - lastPingSentTime);
        } else {
            rtt = 1;
        }

        currentPingMs = (int) rtt;
        SwingUtilities.invokeLater(() -> updatePingDisplay(currentPingMs));
    }

    private void updatePingDisplay(int pingMs) {
        Color c;
        if (pingMs < 60) {
            c = new Color(34, 197, 94); // Xanh lá
        } else if (pingMs < 120) {
            c = new Color(234, 179, 8); // Vàng
        } else {
            c = new Color(239, 68, 68); // Đỏ
        }
        lblPingBadge.setAccentColor(c);
        lblPingBadge.setText("Ping: " + pingMs + " ms");
    }

    private void updateFpsDisplay(int fps) {
        lblFpsBadge.setText(fps + " FPS");
    }

    public void setOnDisconnectCallback(Runnable onDisconnectCallback) {
        this.onDisconnectCallback = onDisconnectCallback;
    }

    public void setDisconnectCallback(Runnable disconnectCallback) {
        this.disconnectCallback = disconnectCallback;
    }

    public void updateConnection(ConnectionManager newConnection) {
        if (newConnection != null && newConnection.getOutputStream() != null) {
            this.screenPanel.setEventSender(new EventSender(newConnection.getOutputStream()));
        }
    }

    public void startReceiving() {
        new Thread(() -> {
            try {
                DataInputStream in = connectionManager.getInputStream();
                while (connectionManager != null && connectionManager.isConnected()) {
                    PacketParser.Packet packet = PacketParser.read(in);

                    if (packet.type() == PacketType.FRAME_DATA) {
                        byte[] payload = packet.payload();
                        if (payload.length >= 8) {
                            ByteBuffer bb = ByteBuffer.wrap(payload);
                            long timestamp = bb.getLong();
                            byte[] imgData = new byte[payload.length - 8];
                            bb.get(imgData);
                            FramePacket frame = new FramePacket(timestamp, imgData);
                            updateScreen(frame);
                        }
                    } else if (packet.type() == PacketType.PONG) {
                        onPongReceived(packet.payload());
                    } else if (packet.type() == PacketType.DIR_RESP) {
                        if (fileTransferService != null) {
                            fileTransferService.onDirRespReceived(packet.payloadAsString());
                        }
                    } else if (packet.type() == PacketType.FILE_META) {
                        if (fileTransferService != null) {
                            fileTransferService.onFileMetaReceived(packet.payloadAsString());
                        }
                    } else if (packet.type() == PacketType.FILE_CHUNK) {
                        if (fileTransferService != null) {
                            fileTransferService.onFileChunkReceived(packet.payload());
                        }
                    } else if (packet.type() == PacketType.FILE_END) {
                        if (fileTransferService != null) {
                            fileTransferService.onFileEndReceived();
                        }
                    } else if (packet.type() == PacketType.FILE_CANCEL) {
                        if (fileTransferService != null) {
                            fileTransferService.onFileCancelReceived();
                        }
                    } else if (packet.type() == PacketType.CLIPBOARD_SYNC) {
                        setClientClipboardText(packet.payloadAsString());
                    } else if (packet.type() == PacketType.DISCONNECT) {
                        System.out.println("[RemoteViewFrame] Server đã ngắt kết nối.");
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                    "Server đã đóng phiên điều khiển từ xa.",
                                    "Ngắt kết nối", JOptionPane.INFORMATION_MESSAGE);
                            disconnectAndExit();
                        });
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

        frameCount++;
        long now = System.currentTimeMillis();
        long diff = now - lastFpsCalcTime;
        if (diff >= 1000) {
            currentFps = (int) Math.round((frameCount * 1000.0) / diff);
            frameCount = 0;
            lastFpsCalcTime = now;
            SwingUtilities.invokeLater(() -> updateFpsDisplay(currentFps));
        }
    }
}