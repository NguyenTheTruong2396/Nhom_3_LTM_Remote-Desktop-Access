package com.remote.client.ui;

import com.remote.client.network.ConnectionManager;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketType;

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
import java.lang.reflect.Method;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Giao diện Client chính cao cấp tích hợp hiệu ứng trượt mượt mà (Slide Animation 60 FPS):
 * - Bấm SWITCH TO SERVER: Trượt êm sang giao diện Server (không tắt/mở cửa sổ mới).
 * - Bấm SWITCH TO CLIENT: Trượt êm về giao diện Client.
 * - Khung xanh bo tròn được sửa lỗi 100% đối xứng hoàn hảo.
 * - Tự động nhận diện Server qua UDP LAN, chỉ cần nhập ID và Passcode!
 */
public class ClientFrame extends JFrame {

    private static final int WIN_W = 900;
    private static final int WIN_H = 530;

    private static final Color ORANGE_START = new Color(0xFF, 0x7E, 0x00); // #FF7E00
    private static final Color ORANGE_END   = new Color(0xFF, 0xA2, 0x33); // #FFA233
    private static final Color BLUE_START   = new Color(0x1B, 0x75, 0xBC); // #1B75BC
    private static final Color BLUE_END     = new Color(0x28, 0x8C, 0xDB); // #288CDB
    private static final Color BLUE_DARK    = new Color(0x0E, 0x4B, 0x7E);

    // Auto-Discovery UDP
    private static final Map<String, String> discoveredServers = new ConcurrentHashMap<>();
    private static volatile String lastKnownServerIp = "127.0.0.1";
    private volatile boolean discoveryRunning = true;
    private DatagramSocket discoverySocket;

    // Server Info
    private String serverId = "--- ---";
    private String serverPasscode = "--- ---";
    private String localIp = "127.0.0.1";

    // Server backend khi switch sang Server
    private Object serverSessionManager;
    private Thread serverThread;
    private volatile boolean serverBroadcastRunning = false;
    private DatagramSocket serverBroadcastSocket;

    // Components Server
    private JLabel lblServerIdText;
    private JLabel lblServerPassText;
    private JLabel lblServerStatus;

    // Container trượt
    private JPanel sliderContainer;
    private JPanel serverPanel;
    private JPanel clientPanel;
    private boolean isClientMode = true; // Mặc định mở ở Client
    private boolean isAnimating = false;

    // Components Client
    private JTextField txtClientId;
    private JPasswordField txtClientPasscode;
    private JButton btnConnect;
    private JLabel lblClientStatus;

    public ClientFrame() {
        this(true);
    }

    public ClientFrame(boolean startInClientMode) {
        setTitle("Remote Desktop Access - HKTNP");
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        initLocalIp();
        startUdpDiscovery();

        buildSlidingUI(startInClientMode);

        getContentPane().setPreferredSize(new Dimension(WIN_W, WIN_H));
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stopServerMode();
                discoveryRunning = false;
                if (discoverySocket != null && !discoverySocket.isClosed()) discoverySocket.close();
            }
        });
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> {
            ClientFrame frame = new ClientFrame(true);
            frame.setVisible(true);
        });
    }

    private void initLocalIp() {
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            localIp = "127.0.0.1";
        }
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
        }, "Client-UDP-Discovery");
        t.setDaemon(true);
        t.start();
    }

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
            startServerMode();
            serverPanel.setBounds(0, 0, WIN_W, WIN_H);
            clientPanel.setBounds(WIN_W, 0, WIN_W, WIN_H);
            serverPanel.setVisible(true);
            clientPanel.setVisible(false);
        }
    }

    public void slideToClient() {
        if (isAnimating || isClientMode) return;
        stopServerMode();
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

    public void slideToServer() {
        if (isAnimating || !isClientMode) return;
        startServerMode();
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

    private synchronized void startServerMode() {
        if (serverSessionManager != null) return;
        try {
            File projectRoot = findProjectRoot();
            File serverClasses = new File(projectRoot, "server/target/classes");
            File commonClasses = new File(projectRoot, "common/target/classes");

            URLClassLoader loader = new URLClassLoader(
                    new URL[]{ serverClasses.toURI().toURL(), commonClasses.toURI().toURL() },
                    Thread.currentThread().getContextClassLoader()
            );

            try {
                Class<?> jniClass = loader.loadClass("com.remote.server.nativeapi.JniBridge");
                jniClass.getMethod("loadNativeLibrary").invoke(null);
            } catch (Exception ignored) {}

            Class<?> smClass = loader.loadClass("com.remote.server.network.SessionManager");
            serverSessionManager = smClass.getConstructor(int.class).newInstance(8888);
            Object sInfo = smClass.getMethod("createSession").invoke(serverSessionManager);

            serverId = (String) sInfo.getClass().getMethod("getSessionId").invoke(sInfo);
            serverPasscode = (String) sInfo.getClass().getMethod("getPasscode").invoke(sInfo);

            // Đăng ký IP làm alias cho ID trong activeSessions để bạn bè kết nối IP hay ID đều được
            try {
                java.lang.reflect.Field f = smClass.getDeclaredField("activeSessions");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, String> map = (Map<String, String>) f.get(serverSessionManager);
                if (map != null && serverPasscode != null) {
                    map.put(localIp, serverPasscode);
                    map.put("127.0.0.1", serverPasscode);
                    try {
                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            NetworkInterface nif = interfaces.nextElement();
                            Enumeration<InetAddress> addrs = nif.getInetAddresses();
                            while (addrs.hasMoreElements()) {
                                InetAddress addr = addrs.nextElement();
                                if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                                    map.put(addr.getHostAddress(), serverPasscode);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            if (lblServerIdText != null) lblServerIdText.setText("ID: " + serverId);
            if (lblServerPassText != null) lblServerPassText.setText("Passcode: " + serverPasscode);
            if (lblServerStatus != null) lblServerStatus.setText("● Sẵn sàng kết nối | IP Server: " + localIp + " (Port: 8888)");

            Method startMethod = smClass.getMethod("start");
            serverThread = new Thread(() -> {
                try { startMethod.invoke(serverSessionManager); } catch (Exception ignored) {}
            }, "ClientFrame-ServerHost-Thread");
            serverThread.setDaemon(true);
            serverThread.start();

            startServerBroadcast(serverId);

        } catch (Throwable e) {
            e.printStackTrace();
            serverId = "ERR-PORT";
            serverPasscode = "8888";
            if (lblServerIdText != null) lblServerIdText.setText("ID: Lỗi mở cổng 8888");
            if (lblServerPassText != null) lblServerPassText.setText("Passcode: Kiểm tra cổng 8888");
        }
    }

    private synchronized void stopServerMode() {
        serverBroadcastRunning = false;
        if (serverBroadcastSocket != null && !serverBroadcastSocket.isClosed()) {
            serverBroadcastSocket.close();
            serverBroadcastSocket = null;
        }
        if (serverSessionManager != null) {
            try {
                serverSessionManager.getClass().getMethod("stop").invoke(serverSessionManager);
            } catch (Exception ignored) {}
            serverSessionManager = null;
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            serverThread = null;
        }
    }

    private void startServerBroadcast(String sId) {
        serverBroadcastRunning = true;
        Thread t = new Thread(() -> {
            try {
                serverBroadcastSocket = new DatagramSocket();
                serverBroadcastSocket.setBroadcast(true);

                while (serverBroadcastRunning && serverSessionManager != null) {
                    try {
                        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            NetworkInterface nif = interfaces.nextElement();
                            if (nif.isLoopback() || !nif.isUp()) continue;
                            for (InterfaceAddress ifAddr : nif.getInterfaceAddresses()) {
                                InetAddress addr = ifAddr.getAddress();
                                if (addr instanceof Inet4Address) {
                                    String payload = "HKTNP_SERVER|" + sId + "|" + addr.getHostAddress() + "|8888";
                                    byte[] buffer = payload.getBytes(StandardCharsets.UTF_8);

                                    InetAddress bcast = ifAddr.getBroadcast();
                                    if (bcast != null) {
                                        try {
                                            serverBroadcastSocket.send(new DatagramPacket(buffer, buffer.length, bcast, 8889));
                                        } catch (Exception ignored) {}
                                    }
                                    try {
                                        serverBroadcastSocket.send(new DatagramPacket(buffer, buffer.length, InetAddress.getByName("255.255.255.255"), 8889));
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
        }, "ClientFrame-Server-Broadcast");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // CLIENT VIEW (NỬA TRÁI TRẮNG, NỬA PHẢI XANH BO TRÒN CHUẨN XÁC)
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

        // Nửa phải màu xanh bo góc hoàn hảo
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
     * KHẮC PHỤC TRIỆT ĐỂ LỖI BO TRÒN: Đường viền trái mềm mại với 2 góc bo đối xứng!
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

        // CHỈ CÓ ĐÚNG 2 Ô NHẬP ID VÀ PASSCODE DUY NHẤT!
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
    // SERVER VIEW (NỬA TRÁI CAM, NỬA PHẢI TRẮNG)
    // =========================================================================

    private JPanel createServerPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(Color.WHITE);
        panel.setSize(WIN_W, WIN_H);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;

        gbc.gridx = 0;
        gbc.weightx = 0.47;
        panel.add(createServerLeftOrangePanel(), gbc);

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

        lblServerIdText = new JLabel("ID: " + serverId);
        lblServerIdText.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblServerIdText.setForeground(Color.WHITE);
        JPanel idBox = createRoundedDisplayBox(lblServerIdText, () -> copyToClipboard(serverId, "Đã sao chép ID!"));

        lblServerPassText = new JLabel("Passcode: " + serverPasscode);
        lblServerPassText.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblServerPassText.setForeground(Color.WHITE);
        JPanel passBox = createRoundedDisplayBox(lblServerPassText, () -> copyToClipboard(serverPasscode, "Đã sao chép Passcode!"));

        centerPanel.add(idBox);
        centerPanel.add(Box.createVerticalStrut(20));
        centerPanel.add(passBox);
        centerPanel.add(Box.createVerticalGlue());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottomPanel.setOpaque(false);

        lblServerStatus = new JLabel("● Sẵn sàng kết nối | IP Server: " + localIp + " (Port: 8888)");
        lblServerStatus.setFont(new Font("Segoe UI", Font.BOLD, 12));
        lblServerStatus.setForeground(new Color(255, 255, 255, 230));
        bottomPanel.add(lblServerStatus);

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
    // CÁC THÀNH PHẦN NÚT BẤM VÀ Ô NHẬP LIỆU
    // =========================================================================

    private JPanel createRoundedDisplayBox(JLabel label, Runnable onClickCopy) {
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

        SwingWorker<ConnectionManager, String> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected ConnectionManager doInBackground() {
                publish("Đang tìm máy Server trên mạng...");

                // 1. Nếu đã nhận dạng qua UDP broadcast
                if (discoveredServers.containsKey(reqSessionId)) {
                    String host = discoveredServers.get(reqSessionId);
                    publish("Đã tìm thấy Server (" + host + "), đang kết nối...");
                    ConnectionManager conn = tryConnect(host, reqSessionId, reqPasscode);
                    if (conn != null) return conn;
                }

                // 2. Thử các máy chủ vừa phát hiện trong discoveredServers
                for (String host : discoveredServers.values()) {
                    publish("Đang thử kết nối tới " + host + "...");
                    ConnectionManager conn = tryConnect(host, reqSessionId, reqPasscode);
                    if (conn != null) return conn;
                }

                // 3. Thử máy chủ ZeroTier mặc định (đã được lưu trong ClientMain gốc)
                publish("Đang kiểm tra kết nối ZeroTier...");
                ConnectionManager connZero = tryConnect("10.208.144.181", reqSessionId, reqPasscode);
                if (connZero != null) {
                    discoveredServers.put(reqSessionId, "10.208.144.181");
                    return connZero;
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
                    ConnectionManager connDirect = tryConnect(host, sId, reqPasscode);
                    if (connDirect != null) return connDirect;
                }

                // 5. Tự động dò quét toàn bộ dải mạng ZeroTier / LAN để tìm Server
                publish("Đang quét tự động mạng tìm máy Server (Port 8888)...");
                List<String> candidateIps = findSubnetCandidateIps();
                for (String candIp : candidateIps) {
                    publish("Đang thử kết nối máy " + candIp + "...");
                    ConnectionManager c = tryConnect(candIp, reqSessionId, reqPasscode);
                    if (c != null) {
                        discoveredServers.put(reqSessionId, candIp);
                        return c;
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
                    ConnectionManager connection = get();
                    if (connection != null) {
                        lblClientStatus.setText("Kết nối thành công! Đang mở màn hình điều khiển...");
                        lblClientStatus.setForeground(Color.GREEN);
                        discoveryRunning = false;
                        if (discoverySocket != null && !discoverySocket.isClosed()) discoverySocket.close();

                        RemoteViewFrame viewFrame = new RemoteViewFrame(connection);
                        viewFrame.setVisible(true);
                        viewFrame.startReceiving();
                        ClientFrame.this.setVisible(false);
                    } else {
                        lblClientStatus.setText(errorMessage != null ? errorMessage : "Kết nối thất bại! Kiểm tra ID và Passcode.");
                        lblClientStatus.setForeground(new Color(255, 120, 120));
                        JOptionPane.showMessageDialog(ClientFrame.this,
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

    private ConnectionManager tryConnect(String host, String sessionId, String passcode) {
        if (host == null || host.trim().isEmpty()) return null;
        try {
            ConnectionManager conn = new ConnectionManager(host.trim(), 8888);
            conn.connect();

            DataInputStream dis = conn.getInputStream();
            DataOutputStream dos = conn.getOutputStream();

            String authPayload = sessionId + "|" + passcode;
            byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);
            PacketBuilder.write(dos, PacketType.AUTH_REQ, payloadBytes);

            PacketParser.Packet resp = PacketParser.read(dis);
            if (resp.type() == PacketType.AUTH_RESP && resp.payload().length > 0 && resp.payload()[0] == 1) {
                return conn;
            } else {
                conn.disconnect();
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

    private File findProjectRoot() {
        try {
            File userDir = new File(System.getProperty("user.dir", "."));
            if (new File(userDir, "client").exists() && new File(userDir, "server").exists()) return userDir;
            if (userDir.getParentFile() != null && new File(userDir.getParentFile(), "client").exists() && new File(userDir.getParentFile(), "server").exists()) {
                return userDir.getParentFile();
            }
            File loc = new File(ClientFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
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
