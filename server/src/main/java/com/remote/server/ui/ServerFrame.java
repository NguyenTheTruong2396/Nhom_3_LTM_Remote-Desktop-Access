package com.remote.server.ui;

import com.remote.server.nativeapi.JniBridge;
import com.remote.server.network.SessionManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Giao diện chính cao cấp tích hợp hiệu ứng trượt mượt mà (Slide Animation 60 FPS):
 * - Bấm SWITCH TO CLIENT: Trượt êm sang giao diện Client (không tắt/mở cửa sổ mới).
 * - Bấm SWITCH TO SERVER: Trượt êm về giao diện Server.
 * - Khung xanh bo tròn bên Client chuẩn xác đối xứng 100%.
 * - Tự động phát hiện Server qua UDP LAN, Client chỉ cần nhập ID và Passcode!
 */
public class ServerFrame extends JFrame {

    private static final int WIN_W = 900;
    private static final int WIN_H = 530;

    private static final Color ORANGE_START = new Color(0xFF, 0x7E, 0x00); // #FF7E00
    private static final Color ORANGE_END   = new Color(0xFF, 0xA2, 0x33); // #FFA233
    private static final Color BLUE_START   = new Color(0x1B, 0x75, 0xBC); // #1B75BC
    private static final Color BLUE_END     = new Color(0x28, 0x8C, 0xDB); // #288CDB
    private static final Color BLUE_DARK    = new Color(0x0E, 0x4B, 0x7E);

    // Backend Server
    private SessionManager sessionManager;
    private SessionManager.SessionInfo sessionInfo;
    private String localIp = "127.0.0.1";
    private volatile boolean broadcastRunning = true;
    private DatagramSocket broadcastSocket;

    // Backend Client Auto-Discovery
    private static final Map<String, String> discoveredServers = new ConcurrentHashMap<>();
    private static volatile String lastKnownServerIp = "127.0.0.1";
    private volatile boolean discoveryRunning = true;
    private DatagramSocket discoverySocket;

    // Container trượt hiệu ứng
    private JPanel sliderContainer;
    private JPanel serverPanel;
    private JPanel clientPanel;
    private boolean isClientMode = false;
    private boolean isAnimating = false;

    // Components Client
    private JTextField txtClientId;
    private JPasswordField txtClientPasscode;
    private JButton btnConnect;
    private JLabel lblClientStatus;

    public ServerFrame() {
        this(false);
    }

    public ServerFrame(boolean startInClientMode) {
        setTitle("Remote Desktop Access - HKTNP");
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        initServerBackend();
        startUdpBroadcast();
        startUdpDiscovery();

        buildSlidingUI(startInClientMode);

        getContentPane().setPreferredSize(new Dimension(WIN_W, WIN_H));
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopAll();
            }
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            ServerFrame frame = new ServerFrame(false);
            frame.setVisible(true);
        });
    }

    private void initServerBackend() {
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            localIp = "127.0.0.1";
        }

        JniBridge.loadNativeLibrary();

        sessionManager = new SessionManager(8888);
        sessionInfo = sessionManager.createSession();

        // Đăng ký IP làm alias cho ID trong activeSessions
        try {
            Field f = SessionManager.class.getDeclaredField("activeSessions");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) f.get(sessionManager);
            if (map != null && sessionInfo != null) {
                map.put(localIp, sessionInfo.getPasscode());
                map.put("127.0.0.1", sessionInfo.getPasscode());
                try {
                    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        NetworkInterface nif = interfaces.nextElement();
                        Enumeration<InetAddress> addrs = nif.getInetAddresses();
                        while (addrs.hasMoreElements()) {
                            InetAddress addr = addrs.nextElement();
                            if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                map.put(addr.getHostAddress(), sessionInfo.getPasscode());
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        Thread serverThread = new Thread(() -> sessionManager.start(), "Server-Listen-Thread");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void startUdpBroadcast() {
        Thread t = new Thread(() -> {
            try {
                broadcastSocket = new DatagramSocket();
                broadcastSocket.setBroadcast(true);

                while (broadcastRunning && sessionInfo != null) {
                    try {
                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            NetworkInterface nif = interfaces.nextElement();
                            if (nif.isLoopback() || !nif.isUp()) continue;
                            for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                                InetAddress addr = ifAddr.getAddress();
                                if (addr instanceof Inet4Address) {
                                    String payload = "HKTNP_SERVER|" + sessionInfo.getSessionId() + "|" + addr.getHostAddress() + "|8888";
                                    byte[] buffer = payload.getBytes(StandardCharsets.UTF_8);

                                    InetAddress bcast = ifAddr.getBroadcast();
                                    if (bcast != null) {
                                        try {
                                            broadcastSocket.send(new DatagramPacket(buffer, buffer.length, bcast, 8889));
                                        } catch (Exception ignored) {}
                                    }
                                    try {
                                        broadcastSocket.send(new DatagramPacket(buffer, buffer.length, InetAddress.getByName("255.255.255.255"), 8889));
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception ignored) {
                        try { Thread.sleep(2000); } catch (Exception ignored2) {}
                    }
                }
            } catch (Exception ignored) {}
        }, "Server-Broadcast");
        t.setDaemon(true);
        t.start();
    }

    private void startUdpDiscovery() {
        Thread t = new Thread(() -> {
            try {
                discoverySocket = new DatagramSocket(null);
                discoverySocket.setReuseAddress(true);
                discoverySocket.bind(new InetSocketAddress(8889));

                byte[] buffer = new byte[1024];
                while (discoveryRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        discoverySocket.receive(packet);

                        String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                        if (message.startsWith("HKTNP_SERVER|")) {
                            String[] parts = message.split("\\|");
                            if (parts.length >= 3) {
                                String sId = parts[1].trim().toUpperCase();
                                String sIp = parts[2].trim();
                                discoveredServers.put(sId, sIp);
                                lastKnownServerIp = sIp;
                            }
                        }
                    } catch (SocketException e) {
                        break;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }, "Client-Discovery");
        t.setDaemon(true);
        t.start();
    }

    private void stopAll() {
        broadcastRunning = false;
        discoveryRunning = false;
        if (broadcastSocket != null && !broadcastSocket.isClosed()) broadcastSocket.close();
        if (discoverySocket != null && !discoverySocket.isClosed()) discoverySocket.close();
        if (sessionManager != null) sessionManager.stop();
    }

    /**
     * Khởi tạo giao diện chứa 2 màn hình Server & Client trượt qua trượt lại trong 1 cửa sổ duy nhất.
     */
    private void buildSlidingUI(boolean startInClientMode) {
        this.isClientMode = startInClientMode;

        sliderContainer = new JPanel(null);
        sliderContainer.setBackground(Color.WHITE);
        setContentPane(sliderContainer);

        serverPanel = createServerPanel();
        clientPanel = createClientPanel();

        sliderContainer.add(serverPanel);
        sliderContainer.add(clientPanel);

        if (startInClientMode) {
            serverPanel.setBounds(-WIN_W, 0, WIN_W, WIN_H);
            clientPanel.setBounds(0, 0, WIN_W, WIN_H);
            serverPanel.setVisible(false);
            clientPanel.setVisible(true);
        } else {
            serverPanel.setBounds(0, 0, WIN_W, WIN_H);
            clientPanel.setBounds(WIN_W, 0, WIN_W, WIN_H);
            serverPanel.setVisible(true);
            clientPanel.setVisible(false);
        }
    }

    /**
     * Hiệu ứng trượt mượt mà 60 FPS (Cubic Ease-Out) sang Client.
     */
    public void slideToClient() {
        if (isAnimating || isClientMode) return;
        isAnimating = true;

        serverPanel.setVisible(true);
        clientPanel.setVisible(true);

        final int startX = 0;
        final int targetX = -WIN_W;
        final int totalFrames = 25;
        final int delay = 10;

        Timer timer = new Timer(delay, null);
        final int[] frame = {0};

        timer.addActionListener(e -> {
            frame[0]++;
            float progress = (float) frame[0] / totalFrames;
            if (progress > 1.0f) progress = 1.0f;

            // Ease-out cubic
            float ease = 1.0f - (float) Math.pow(1.0f - progress, 3);
            int curX = (int) (startX + (targetX - startX) * ease);

            serverPanel.setBounds(curX, 0, WIN_W, WIN_H);
            clientPanel.setBounds(curX + WIN_W, 0, WIN_W, WIN_H);
            sliderContainer.repaint();

            if (progress >= 1.0f) {
                timer.stop();
                serverPanel.setBounds(-WIN_W, 0, WIN_W, WIN_H);
                clientPanel.setBounds(0, 0, WIN_W, WIN_H);
                serverPanel.setVisible(false);
                isClientMode = true;
                isAnimating = false;
            }
        });
        timer.start();
    }

    /**
     * Hiệu ứng trượt mượt mà 60 FPS (Cubic Ease-Out) về Server.
     */
    public void slideToServer() {
        if (isAnimating || !isClientMode) return;
        isAnimating = true;

        serverPanel.setVisible(true);
        clientPanel.setVisible(true);

        final int startX = -WIN_W;
        final int targetX = 0;
        final int totalFrames = 25;
        final int delay = 10;

        Timer timer = new Timer(delay, null);
        final int[] frame = {0};

        timer.addActionListener(e -> {
            frame[0]++;
            float progress = (float) frame[0] / totalFrames;
            if (progress > 1.0f) progress = 1.0f;

            float ease = 1.0f - (float) Math.pow(1.0f - progress, 3);
            int curX = (int) (startX + (targetX - startX) * ease);

            serverPanel.setBounds(curX, 0, WIN_W, WIN_H);
            clientPanel.setBounds(curX + WIN_W, 0, WIN_W, WIN_H);
            sliderContainer.repaint();

            if (progress >= 1.0f) {
                timer.stop();
                serverPanel.setBounds(0, 0, WIN_W, WIN_H);
                clientPanel.setBounds(WIN_W, 0, WIN_W, WIN_H);
                clientPanel.setVisible(false);
                isClientMode = false;
                isAnimating = false;
            }
        });
        timer.start();
    }

    // =========================================================================
    // 1. TẠO GIAO DIỆN SERVER PANEL (BÊN TRÁI MÀU CAM, BÊN PHẢI MÀU TRẮNG)
    // =========================================================================

    private JPanel createServerPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setSize(WIN_W, WIN_H);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;

        // Nửa trái màu cam bo góc
        gbc.gridx = 0;
        gbc.weightx = 0.47;
        panel.add(createServerLeftOrangePanel(), gbc);

        // Nửa phải màu trắng
        gbc.gridx = 1;
        gbc.weightx = 0.53;
        panel.add(createServerRightWhitePanel(), gbc);

        return panel;
    }

    private JPanel createServerLeftOrangePanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int radius = 48;

                // Bo tròn mềm mại góc trên phải và dưới phải
                Path2D.Float path = new Path2D.Float();
                path.moveTo(0, 0);
                path.lineTo(w - radius, 0);
                path.quadTo(w, 0, w, radius);
                path.lineTo(w, h - radius);
                path.quadTo(w, h, w - radius, h);
                path.lineTo(0, h);
                path.closePath();

                GradientPaint gp = new GradientPaint(0, 0, ORANGE_START, 0, h, ORANGE_END);
                g2.setPaint(gp);
                g2.fill(path);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout());
        panel.setBorder(new EmptyBorder(32, 40, 32, 35));

        JLabel lblTitle = new JLabel("Server");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 42));
        lblTitle.setForeground(Color.WHITE);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        String idText = sessionInfo != null ? sessionInfo.getSessionId() : "--- --- ---";
        JPanel idBox = createRoundedDisplayBox("ID: " + idText, () -> copyToClipboard(idText, "Đã sao chép ID!"));

        String passText = sessionInfo != null ? sessionInfo.getPasscode() : "--- ---";
        JPanel passBox = createRoundedDisplayBox("Passcode: " + passText, () -> copyToClipboard(passText, "Đã sao chép Passcode!"));

        centerPanel.add(idBox);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(passBox);
        centerPanel.add(Box.createVerticalGlue());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottomPanel.setOpaque(false);

        JLabel lblStatus = new JLabel("● Sẵn sàng kết nối | IP Server: " + localIp + " (Port: 8888)");
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblStatus.setForeground(new Color(255, 255, 255, 230));
        bottomPanel.add(lblStatus);

        panel.add(lblTitle, BorderLayout.NORTH);
        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createServerRightWhitePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(25, 30, 35, 35));

        JPanel topLogoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        topLogoPanel.setOpaque(false);
        topLogoPanel.add(new HktnpLogoComponent());
        panel.add(topLogoPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        JLabel lblBrand = new JLabel("REMOTE DESKTOP ACCESS");
        lblBrand.setFont(new Font("Segoe UI", Font.BOLD, 22));
        lblBrand.setForeground(BLUE_START);
        lblBrand.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton btnSwitch = createPillButton("SWITCH TO CLIENT", BLUE_START);
        btnSwitch.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnSwitch.addActionListener(e -> slideToClient());

        centerPanel.add(lblBrand);
        centerPanel.add(Box.createVerticalStrut(28));
        centerPanel.add(btnSwitch);
        centerPanel.add(Box.createVerticalGlue());

        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    // =========================================================================
    // 2. TẠO GIAO DIỆN CLIENT PANEL (BÊN TRÁI MÀU TRẮNG, BÊN PHẢI MÀU XANH)
    // =========================================================================

    private JPanel createClientPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setSize(WIN_W, WIN_H);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;

        // Nửa trái màu trắng
        gbc.gridx = 0;
        gbc.weightx = 0.53;
        panel.add(createClientLeftWhitePanel(), gbc);

        // Nửa phải màu xanh bo tròn chuẩn xác
        gbc.gridx = 1;
        gbc.weightx = 0.47;
        panel.add(createClientRightBluePanel(), gbc);

        return panel;
    }

    private JPanel createClientLeftWhitePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(Color.WHITE);
        panel.setBorder(new EmptyBorder(25, 35, 35, 30));

        JPanel topLogoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        topLogoPanel.setOpaque(false);
        topLogoPanel.add(new HktnpLogoComponent());
        panel.add(topLogoPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        JLabel lblBrand = new JLabel("REMOTE DESKTOP ACCESS");
        lblBrand.setFont(new Font("Segoe UI", Font.BOLD, 22));
        lblBrand.setForeground(ORANGE_START);
        lblBrand.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton btnSwitch = createPillButton("SWITCH TO SERVER", ORANGE_START);
        btnSwitch.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnSwitch.addActionListener(e -> slideToServer());

        centerPanel.add(lblBrand);
        centerPanel.add(Box.createVerticalStrut(28));
        centerPanel.add(btnSwitch);
        centerPanel.add(Box.createVerticalGlue());

        panel.add(centerPanel, BorderLayout.CENTER);
        return panel;
    }

    /**
     * SỬA LỖI BO TRÒN: Đường cong bên trái hoàn hảo với cả góc trên và góc dưới!
     */
    private JPanel createClientRightBluePanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int radius = 48;

                // Đường vẽ đối xứng chuẩn xác 100% với panel màu cam
                Path2D.Float path = new Path2D.Float();
                path.moveTo(radius, 0);                 // Bắt đầu mép trên
                path.lineTo(w, 0);                      // Thẳng sang góc trên phải
                path.lineTo(w, h);                      // Thẳng xuống góc dưới phải
                path.lineTo(radius, h);                 // Thẳng sang mép dưới trái
                path.quadTo(0, h, 0, h - radius);       // Bo tròn góc dưới bên trái!
                path.lineTo(0, radius);                 // Thẳng đứng lên dọc cạnh trái!
                path.quadTo(0, 0, radius, 0);           // Bo tròn góc trên bên trái!
                path.closePath();

                GradientPaint gp = new GradientPaint(0, 0, BLUE_START, 0, h, BLUE_END);
                g2.setPaint(gp);
                g2.fill(path);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout());
        panel.setBorder(new EmptyBorder(32, 35, 32, 40));

        JLabel lblTitle = new JLabel("Client");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 42));
        lblTitle.setForeground(Color.WHITE);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        // CHỈ CÓ 2 Ô NHẬP ID VÀ PASSCODE DUY NHẤT!
        txtClientId = new JTextField();
        JPanel idInputBox = createRoundedInputFieldBox("ID:", txtClientId);

        txtClientPasscode = new JPasswordField();
        JPanel passInputBox = createRoundedInputFieldBox("Passcode:", txtClientPasscode);

        btnConnect = createSolidConnectButton();
        btnConnect.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnConnect.addActionListener(e -> performClientConnect());

        txtClientPasscode.addActionListener(e -> performClientConnect());
        txtClientId.addActionListener(e -> performClientConnect());

        centerPanel.add(idInputBox);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(passInputBox);
        centerPanel.add(Box.createVerticalStrut(26));
        centerPanel.add(btnConnect);
        centerPanel.add(Box.createVerticalGlue());

        lblClientStatus = new JLabel("Nhập ID & Passcode hiển thị ở máy Server để kết nối", SwingConstants.CENTER);
        lblClientStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblClientStatus.setForeground(new Color(255, 255, 255, 220));

        panel.add(lblTitle, BorderLayout.NORTH);
        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(lblClientStatus, BorderLayout.SOUTH);

        return panel;
    }

    // =========================================================================
    // 3. CÁC COMPONENT GIAO DIỆN PHỤ TRỢ (NÚT BẤM, KHUNG NHẬP LIỆU)
    // =========================================================================

    private JPanel createRoundedDisplayBox(String text, Runnable onClickCopy) {
        JPanel box = new JPanel(new BorderLayout(14, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = 28;

                g2.setColor(new Color(255, 255, 255, 40));
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, arc, arc));

                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setPreferredSize(new Dimension(340, 54));
        box.setMaximumSize(new Dimension(360, 54));
        box.setBorder(new EmptyBorder(0, 20, 0, 12));

        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 18));
        label.setForeground(Color.WHITE);
        box.add(label, BorderLayout.CENTER);

        JButton btnCopy = new JButton("Copy") {
            private boolean isHovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { isHovered = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { isHovered = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();

                g2.setColor(isHovered ? new Color(245, 245, 245) : Color.WHITE);
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tx = (w - fm.stringWidth(getText())) / 2;
                int ty = (h - fm.getHeight()) / 2 + fm.getAscent();

                g2.setColor(ORANGE_START);
                g2.drawString(getText(), tx, ty);
                g2.dispose();
            }
        };

        btnCopy.setFont(new Font("Segoe UI", Font.BOLD, 12));
        btnCopy.setPreferredSize(new Dimension(56, 28));
        btnCopy.setOpaque(false);
        btnCopy.setContentAreaFilled(false);
        btnCopy.setBorderPainted(false);
        btnCopy.setFocusPainted(false);
        btnCopy.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btnCopy.addActionListener(e -> onClickCopy.run());

        box.add(btnCopy, BorderLayout.EAST);
        return box;
    }

    private JPanel createRoundedInputFieldBox(String labelPrefix, JTextField textField) {
        JPanel box = new JPanel(new BorderLayout(14, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = 28;

                g2.setColor(new Color(255, 255, 255, 40));
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.5f));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, arc, arc));

                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setPreferredSize(new Dimension(340, 54));
        box.setMaximumSize(new Dimension(360, 54));
        box.setBorder(new EmptyBorder(0, 20, 0, 16));

        JLabel lbl = new JLabel(labelPrefix);
        lbl.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lbl.setForeground(Color.WHITE);
        box.add(lbl, BorderLayout.WEST);

        textField.setOpaque(false);
        textField.setBorder(null);
        textField.setFont(new Font("Segoe UI", Font.BOLD, 17));
        textField.setForeground(Color.WHITE);
        textField.setCaretColor(Color.WHITE);
        box.add(textField, BorderLayout.CENTER);

        return box;
    }

    private JButton createSolidConnectButton() {
        JButton btn = new JButton("CONNECT") {
            private boolean isHovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { isHovered = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { isHovered = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = h - 2;

                g2.setColor(isHovered ? new Color(242, 246, 252) : Color.WHITE);
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (w - fm.stringWidth(getText())) / 2;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();

                g2.setColor(BLUE_START);
                g2.drawString(getText(), textX, textY);
                g2.dispose();
            }
        };

        btn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btn.setPreferredSize(new Dimension(180, 44));
        btn.setMaximumSize(new Dimension(200, 44));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private JButton createPillButton(String text, Color primaryColor) {
        JButton btn = new JButton(text) {
            private boolean isHovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { isHovered = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { isHovered = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = h - 2;

                if (isHovered) {
                    g2.setColor(new Color(primaryColor.getRed(), primaryColor.getGreen(), primaryColor.getBlue(), 30));
                    g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));
                }

                g2.setColor(primaryColor);
                g2.setStroke(new BasicStroke(2.0f));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int textX = (w - fm.stringWidth(getText())) / 2;
                int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), textX, textY);

                g2.dispose();
            }
        };

        btn.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btn.setForeground(primaryColor);
        btn.setPreferredSize(new Dimension(210, 46));
        btn.setMaximumSize(new Dimension(230, 46));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void copyToClipboard(String content, String notifyMsg) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
        JOptionPane.showMessageDialog(this, notifyMsg + "\n" + content, "Đã sao chép", JOptionPane.INFORMATION_MESSAGE);
    }

    // =========================================================================
    // 4. KẾT NỐI CLIENT TỰ ĐỘNG KHÔNG CẦN NHẬP IP
    // =========================================================================

    private void performClientConnect() {
        String inputId = txtClientId.getText().trim();
        String passcode = new String(txtClientPasscode.getPassword()).trim();

        if (inputId.isEmpty() || passcode.isEmpty()) {
            lblClientStatus.setText("Vui lòng nhập ID và Passcode của máy Server!");
            lblClientStatus.setForeground(Color.YELLOW);
            return;
        }

        btnConnect.setEnabled(false);
        lblClientStatus.setText("Đang tìm kiếm máy Server...");
        lblClientStatus.setForeground(Color.WHITE);

        final String reqSessionId = inputId.toUpperCase();
        final String reqPasscode = passcode;

        SwingWorker<Socket, String> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected Socket doInBackground() {
                publish("Đang tìm máy Server trên mạng...");

                // 1. Nếu đã nhận dạng qua UDP broadcast
                if (discoveredServers.containsKey(reqSessionId)) {
                    String host = discoveredServers.get(reqSessionId);
                    publish("Đã tìm thấy Server (" + host + "), đang kết nối...");
                    Socket s = tryConnectSocket(host, reqSessionId, reqPasscode);
                    if (s != null) return s;
                }

                // 2. Thử các máy chủ trong discoveredServers
                for (String host : discoveredServers.values()) {
                    publish("Đang thử kết nối tới " + host + "...");
                    Socket s = tryConnectSocket(host, reqSessionId, reqPasscode);
                    if (s != null) return s;
                }

                // 3. Thử máy chủ ZeroTier mặc định
                publish("Đang kiểm tra kết nối ZeroTier...");
                Socket sZero = tryConnectSocket("10.208.144.181", reqSessionId, reqPasscode);
                if (sZero != null) {
                    discoveredServers.put(reqSessionId, "10.208.144.181");
                    return sZero;
                }

                // 4. Nếu người dùng nhập trực tiếp IP (hoặc ID@IP)
                if (inputId.contains(".")) {
                    String host = inputId;
                    String sId = reqSessionId;
                    if (inputId.contains("@")) {
                        String[] p = inputId.split("@", 2);
                        sId = p[0].trim().toUpperCase();
                        host = p[1].trim();
                    }
                    publish("Đang kết nối tới " + host + "...");
                    Socket sDirect = tryConnectSocket(host, sId, reqPasscode);
                    if (sDirect != null) return sDirect;
                }

                // 5. Tự động quét dải mạng ZeroTier / LAN tìm Server
                publish("Đang quét tự động mạng tìm máy Server (Port 8888)...");
                List<String> candidateIps = findSubnetCandidateIps();
                for (String candIp : candidateIps) {
                    publish("Đang thử kết nối máy " + candIp + "...");
                    Socket s = tryConnectSocket(candIp, reqSessionId, reqPasscode);
                    if (s != null) {
                        discoveredServers.put(reqSessionId, candIp);
                        return s;
                    }
                }

                errorMessage = "Không tìm thấy Server khớp với ID [" + inputId + "] trên mạng!";
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                if (!chunks.isEmpty()) {
                    lblClientStatus.setText(chunks.get(chunks.size() - 1));
                }
            }

            @Override
            protected void done() {
                btnConnect.setEnabled(true);
                try {
                    Socket socket = get();
                    if (socket != null) {
                        lblClientStatus.setText("Kết nối thành công! Đang mở màn hình...");
                        openRemoteView(socket);
                        ServerFrame.this.setVisible(false);
                    } else {
                        lblClientStatus.setText(errorMessage != null ? errorMessage : "Kết nối thất bại! Kiểm tra ID và Passcode.");
                        lblClientStatus.setForeground(new Color(255, 120, 120));
                        JOptionPane.showMessageDialog(ServerFrame.this,
                                (errorMessage != null ? errorMessage : "Kết nối thất bại!") +
                                "\n\n💡 Lưu ý:\n- Đảm bảo máy Server đã bật và mở port 8888.\n- Đảm bảo 2 máy kết nối chung Wi-Fi hoặc ZeroTier.",
                                "Kết nối thất bại", JOptionPane.ERROR_MESSAGE);
                    }
                } catch (Exception e) {
                    lblClientStatus.setText("Lỗi: " + e.getMessage());
                    lblClientStatus.setForeground(new Color(255, 120, 120));
                }
            }
        };

        worker.execute();
    }

    private Socket tryConnectSocket(String host, String sessionId, String passcode) {
        if (host == null || host.trim().isEmpty()) return null;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host.trim(), 8888), 1200);

            DataInputStream dis = new DataInputStream(s.getInputStream());
            DataOutputStream dos = new DataOutputStream(s.getOutputStream());

            String authPayload = sessionId + "|" + passcode;
            byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);

            dos.writeByte(0x01); // AUTH_REQ
            dos.writeInt(payloadBytes.length);
            dos.write(payloadBytes);
            dos.flush();

            byte respType = dis.readByte();
            int length = dis.readInt();
            byte[] respPayload = new byte[length];
            if (length > 0) dis.readFully(respPayload);

            if (respType == 0x02 && respPayload.length > 0 && respPayload[0] == 1) {
                return s;
            } else {
                s.close();
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> findSubnetCandidateIps() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                    InetAddress addr = ifAddr.getAddress();
                    if (addr instanceof Inet4Address) {
                        String myIp = addr.getHostAddress();
                        int lastDot = myIp.lastIndexOf('.');
                        if (lastDot > 0) {
                            String prefix = myIp.substring(0, lastDot + 1);
                            ExecutorService pool = Executors.newFixedThreadPool(40);
                            List<Future<String>> futures = new ArrayList<>();
                            for (int i = 1; i <= 254; i++) {
                                String targetIp = prefix + i;
                                if (targetIp.equals(myIp)) continue;
                                futures.add(pool.submit(() -> {
                                    try (Socket s = new Socket()) {
                                        s.connect(new InetSocketAddress(targetIp, 8888), 350);
                                        return targetIp;
                                    } catch (Exception e) {
                                        return null;
                                    }
                                }));
                            }
                            pool.shutdown();
                            try {
                                pool.awaitTermination(1500, TimeUnit.MILLISECONDS);
                            } catch (Exception ignored) {}
                            for (Future<String> f : futures) {
                                try {
                                    if (f.isDone()) {
                                        String openIp = f.get();
                                        if (openIp != null) list.add(openIp);
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    /**
     * Mở RemoteViewFrame dùng Dynamic Classloading để không phụ thuộc cứng lúc compile
     */
    private void openRemoteView(Socket socket) {
        try {
            // Nạp ConnectionManager
            File projectRoot = findProjectRoot();
            File clientClasses = new File(projectRoot, "client/target/classes");
            File commonClasses = new File(projectRoot, "common/target/classes");

            URLClassLoader loader = new URLClassLoader(
                    new URL[]{ clientClasses.toURI().toURL(), commonClasses.toURI().toURL() },
                    Thread.currentThread().getContextClassLoader()
            );

            Class<?> cmClass = loader.loadClass("com.remote.client.network.ConnectionManager");
            Object cm = cmClass.getConstructor(String.class, int.class).newInstance("127.0.0.1", 8888);

            // Gán socket đã kết nối vào ConnectionManager qua reflection
            Field fSocket = cmClass.getDeclaredField("socket");
            fSocket.setAccessible(true);
            fSocket.set(cm, socket);

            Field fIn = cmClass.getDeclaredField("inputStream");
            fIn.setAccessible(true);
            fIn.set(cm, new DataInputStream(socket.getInputStream()));

            Field fOut = cmClass.getDeclaredField("outputStream");
            fOut.setAccessible(true);
            fOut.set(cm, new DataOutputStream(socket.getOutputStream()));

            Class<?> rvClass = loader.loadClass("com.remote.client.ui.RemoteViewFrame");
            JFrame viewFrame = (JFrame) rvClass.getConstructor(cmClass).newInstance(cm);
            viewFrame.setVisible(true);

            Method startReceiving = rvClass.getMethod("startReceiving");
            startReceiving.invoke(viewFrame);

        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Lỗi mở cửa sổ điều khiển: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private File findProjectRoot() {
        try {
            File userDir = new File(System.getProperty("user.dir", "."));
            if (new File(userDir, "client").exists() && new File(userDir, "server").exists()) return userDir;
            if (userDir.getParentFile() != null && new File(userDir.getParentFile(), "client").exists() && new File(userDir.getParentFile(), "server").exists()) {
                return userDir.getParentFile();
            }
            File loc = new File(ServerFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File cur = loc;
            while (cur != null) {
                if (new File(cur, "client").exists() && new File(cur, "server").exists()) {
                    return cur;
                }
                cur = cur.getParentFile();
            }
        } catch (Exception ignored) {}
        return new File("N:/netbean_JDK/DAMN/full_UI/Nhom_3_LTM_Remote-Desktop-Access");
    }

    public static class HktnpLogoComponent extends JComponent {
        public HktnpLogoComponent() {
            setPreferredSize(new Dimension(145, 42));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int startX = 6;
            int startY = 8;

            g2.setColor(ORANGE_START);
            int[] xOrange = {startX, startX + 14, startX + 22, startX + 16, startX + 8};
            int[] yOrange = {startY + 18, startY + 8, startY + 16, startY + 22, startY + 24};
            g2.fillPolygon(xOrange, yOrange, xOrange.length);

            g2.setColor(BLUE_START);
            int[] xBlue = {startX + 8, startX + 20, startX + 28, startX + 22, startX + 14};
            int[] yBlue = {startY + 12, startY + 2, startY + 10, startY + 16, startY + 18};
            g2.fillPolygon(xBlue, yBlue, xBlue.length);

            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(startX + 18, startY + 10, startX + 28, startY + 2);
            g2.drawLine(startX + 22, startY + 2, startX + 28, startY + 2);
            g2.drawLine(startX + 28, startY + 2, startX + 28, startY + 8);

            g2.setColor(BLUE_DARK);
            g2.setFont(new Font("Segoe UI", Font.BOLD, 21));
            g2.drawString("HKTNP", startX + 34, startY + 22);

            g2.dispose();
        }
    }
}
