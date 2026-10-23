package com.remote.ui;

import com.remote.client.network.ConnectionManager;
import com.remote.client.ui.RemoteViewFrame;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketParser;
import com.remote.common.network.PacketType;
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
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Giao diện chính (MainFrame) chuẩn chỉnh theo yêu cầu:
 * - Mặc định mở Server (thẻ màu cam bên trái, logo và nút chuyển bên phải).
 * - Hiệu ứng trượt biến đổi: Khi bấm switch, thẻ màu cam trượt từ trái sang phải,
 *   đồng thời chuyển mượt thành thẻ màu xanh (Client) và ngược lại.
 * - Thay thế hoàn toàn chữ "Copy" hình tròn bằng Icon sao chép Vector (hai tờ giấy đè lên nhau)
 *   tinh tế, hiện đại, có hiệu ứng đổi màu khi sao chép thành công.
 * - Tự động phát hiện Server trong LAN và kết nối mượt mà.
 */
public class MainFrame extends JFrame {

    private static final int WIN_W = 900;
    private static final int WIN_H = 530;
    private static final int CARD_W = 440;
    private static final int BRAND_W = WIN_W - CARD_W; // 460

    private static final Color ORANGE_START = new Color(0xFF, 0x7E, 0x00);
    private static final Color ORANGE_END   = new Color(0xFF, 0xA2, 0x33);
    private static final Color BLUE_START   = new Color(0x1B, 0x75, 0xBC);
    private static final Color BLUE_END     = new Color(0x28, 0x8C, 0xDB);
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

    // Trạng thái Animation trượt
    // 0.0f = Server (Thẻ màu bên Trái, Brand bên Phải)
    // 1.0f = Client (Thẻ màu bên Phải, Brand bên Trái)
    private float animationProgress = 0.0f;
    private boolean isClientMode = false;
    private boolean isAnimating = false;
    private Timer slideTimer;

    // Các thành phần giao diện chính
    private JLayeredPane mainContainer;
    private JPanel coloredCard;
    private CardLayout cardContentLayout;
    private JPanel cardContentPanel;
    private JPanel serverView;
    private JPanel clientView;
    private BrandInfoPanel brandPanel;

    // Components Server
    private JLabel lblServerIdVal;
    private JLabel lblServerPassVal;
    private JLabel lblServerStatus;

    // Components Client
    private JTextField txtClientId;
    private JPasswordField txtClientPasscode;
    private JButton btnConnect;
    private JLabel lblClientStatus;

    public MainFrame() {
        this(false); // Mặc định mở Server
    }

    public MainFrame(boolean startInClientMode) {
        setTitle("Remote Desktop Access - HKTNP");
        setResizable(false);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        initServerBackend();
        startUdpBroadcast();
        startUdpDiscovery();

        buildMorphingSlidingUI(startInClientMode);

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

    private void initServerBackend() {
        try {
            localIp = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ignored) {
            localIp = "127.0.0.1";
        }

        try {
            JniBridge.loadNativeLibrary();
        } catch (Throwable t) {
            System.err.println("[MainFrame] JNI load warning: " + t.getMessage());
        }

        try {
            // Phase 5: dual-socket (control=5002, video=5001)
            sessionManager = new SessionManager(
                    com.remote.common.network.NetworkConstants.DEFAULT_CONTROL_PORT,
                    com.remote.common.network.NetworkConstants.DEFAULT_VIDEO_PORT);
            sessionInfo = sessionManager.createSession();

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
        } catch (Throwable e) {
            System.err.println("[MainFrame] Lỗi khởi tạo Server Backend: " + e.getMessage());
        }
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
                        String payload = "HKTNP_SERVER|" + sessionInfo.getSessionId() + "|" + addr.getHostAddress() + "|5002";
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
        }, "Server-Broadcast-Thread");
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
        }, "Client-Discovery-Thread");
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
     * Xây dựng giao diện trượt đổi màu (Morphing Slide):
     * Thẻ màu trượt từ trái (Cam) sang phải (Xanh) và ngược lại.
     */
    private void buildMorphingSlidingUI(boolean startInClientMode) {
        this.isClientMode = startInClientMode;
        this.animationProgress = startInClientMode ? 1.0f : 0.0f;

        mainContainer = new JLayeredPane();
        mainContainer.setPreferredSize(new Dimension(WIN_W, WIN_H));
        mainContainer.setBackground(Color.WHITE);
        mainContainer.setOpaque(true);
        setContentPane(mainContainer);

        // 1. Thẻ màu động (Colored Card - Cam khi ở Server, Xanh khi ở Client)
        coloredCard = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                // Biến thiên màu sắc mượt mà giữa Cam và Xanh
                Color startC = interpolateColor(ORANGE_START, BLUE_START, animationProgress);
                Color endC   = interpolateColor(ORANGE_END, BLUE_END, animationProgress);

                GradientPaint gp = new GradientPaint(0, 0, startC, 0, h, endC);
                g2.setPaint(gp);

                int radius = 48;
                Path2D.Float path = new Path2D.Float();

                if (animationProgress < 0.5f) {
                    // Thẻ ở bên trái: Bo góc trên-phải và dưới-phải
                    path.moveTo(0, 0);
                    path.lineTo(w - radius, 0);
                    path.quadTo(w, 0, w, radius);
                    path.lineTo(w, h - radius);
                    path.quadTo(w, h, w - radius, h);
                    path.lineTo(0, h);
                    path.closePath();
                } else {
                    // Thẻ ở bên phải: Bo góc trên-trái và dưới-trái đối xứng
                    path.moveTo(radius, 0);
                    path.lineTo(w, 0);
                    path.lineTo(w, h);
                    path.lineTo(radius, h);
                    path.quadTo(0, h, 0, h - radius);
                    path.lineTo(0, radius);
                    path.quadTo(0, 0, radius, 0);
                    path.closePath();
                }

                g2.fill(path);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        coloredCard.setOpaque(false);
        coloredCard.setLayout(new BorderLayout());

        cardContentLayout = new CardLayout();
        cardContentPanel = new JPanel(cardContentLayout);
        cardContentPanel.setOpaque(false);

        serverView = createServerView();
        clientView = createClientView();

        cardContentPanel.add(serverView, "SERVER");
        cardContentPanel.add(clientView, "CLIENT");
        coloredCard.add(cardContentPanel, BorderLayout.CENTER);

        // 2. Panel thương hiệu nền trắng (Brand Panel)
        brandPanel = new BrandInfoPanel(e -> toggleMode());

        mainContainer.add(brandPanel, Integer.valueOf(1));
        mainContainer.add(coloredCard, Integer.valueOf(2));

        updateComponentPositions();
        setCardAndBrandState();
    }

    private void updateComponentPositions() {
        // coloredCard trượt từ x=0 (Server) tới x=BRAND_W (Client)
        int coloredX = (int) (animationProgress * BRAND_W);
        // brandPanel trượt từ x=CARD_W (Server) tới x=0 (Client)
        int brandX = (int) ((1.0f - animationProgress) * CARD_W);

        coloredCard.setBounds(coloredX, 0, CARD_W, WIN_H);
        brandPanel.setBounds(brandX, 0, BRAND_W, WIN_H);
    }

    private void setCardAndBrandState() {
        if (animationProgress < 0.5f) {
            cardContentLayout.show(cardContentPanel, "SERVER");
            brandPanel.setMode(false); // Chế độ Server: Logo bên phải, nút SWITCH TO CLIENT
        } else {
            cardContentLayout.show(cardContentPanel, "CLIENT");
            brandPanel.setMode(true);  // Chế độ Client: Logo bên trái, nút SWITCH TO SERVER
        }
    }

    /**
     * Kích hoạt hiệu ứng trượt đổi màu giữa Server và Client:
     * Màu cam bên Server trượt sang thành màu xanh bên Client và ngược lại!
     */
    public void toggleMode() {
        if (isAnimating) return;

        isAnimating = true;
        final float start = animationProgress;
        final float target = isClientMode ? 0.0f : 1.0f; // Nếu đang Client thì về 0 (Server), ngược lại lên 1 (Client)
        final int totalFrames = 24;
        final int delay = 15;

        slideTimer = new Timer(delay, null);
        final int[] frame = {0};

        slideTimer.addActionListener(e -> {
            frame[0]++;
            float rawT = (float) frame[0] / totalFrames;
            if (rawT > 1.0f) rawT = 1.0f;

            // Đường cong chuyển động Cubic Ease-In-Out cực mượt
            float ease = (float) (rawT < 0.5 ? 4 * Math.pow(rawT, 3) : 1 - Math.pow(-2 * rawT + 2, 3) / 2);

            animationProgress = start + (target - start) * ease;

            updateComponentPositions();
            setCardAndBrandState();
            coloredCard.repaint();
            brandPanel.repaint();
            mainContainer.repaint();

            if (rawT >= 1.0f) {
                slideTimer.stop();
                animationProgress = target;
                isClientMode = (target == 1.0f);
                isAnimating = false;
                updateComponentPositions();
                setCardAndBrandState();
                coloredCard.repaint();
                brandPanel.repaint();
                mainContainer.repaint();
            }
        });

        slideTimer.start();
    }

    // =========================================================================
    // NỘI DUNG SERVER (HIỂN THỊ ID/PASS VỚI ICON COPY VECTOR)
    // =========================================================================

    private JPanel createServerView() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(32, 38, 32, 38));

        JLabel lblTitle = new JLabel("Server");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 44));
        lblTitle.setForeground(Color.WHITE);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        String idText = (sessionInfo != null) ? sessionInfo.getSessionId() : "--- --- ---";
        lblServerIdVal = new JLabel("ID: " + idText);
        lblServerIdVal.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblServerIdVal.setForeground(Color.WHITE);
        JPanel idBox = createInfoBox(lblServerIdVal, () -> copyToClipboard(sessionInfo != null ? sessionInfo.getSessionId() : idText, "Đã sao chép ID!"));

        String passText = (sessionInfo != null) ? sessionInfo.getPasscode() : "--- ---";
        lblServerPassVal = new JLabel("Passcode: " + passText);
        lblServerPassVal.setFont(new Font("Segoe UI", Font.BOLD, 18));
        lblServerPassVal.setForeground(Color.WHITE);
        JPanel passBox = createInfoBox(lblServerPassVal, () -> copyToClipboard(sessionInfo != null ? sessionInfo.getPasscode() : passText, "Đã sao chép Passcode!"));

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

    // =========================================================================
    // NỘI DUNG CLIENT (Ô NHẬP LIỆU ID/PASS VÀ NÚT CONNECT)
    // =========================================================================

    private JPanel createClientView() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(32, 38, 32, 38));

        JLabel lblTitle = new JLabel("Client");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 44));
        lblTitle.setForeground(Color.WHITE);

        JPanel centerPanel = new JPanel();
        centerPanel.setOpaque(false);
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.add(Box.createVerticalGlue());

        txtClientId = new JTextField();
        JPanel idInputBox = createInputFieldBox("ID:", txtClientId);

        txtClientPasscode = new JPasswordField();
        JPanel passInputBox = createInputFieldBox("Passcode:", txtClientPasscode);

        btnConnect = createPillConnectButton();
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
    // CÁC HỘP THÔNG TIN VÀ NÚT COPY VECTOR (KHÔNG CÒN HÌNH TRÒN CHỮ COPY)
    // =========================================================================

    /**
     * Tạo hộp hiển thị ID/Passcode với Icon sao chép Vector tinh tế thay thế chữ Copy tròn cũ.
     */
    private JPanel createInfoBox(JLabel label, Runnable onClickCopy) {
        JPanel box = new JPanel(new BorderLayout(10, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = 28;

                g2.setColor(new Color(255, 255, 255, 38));
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.2f));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, arc, arc));

                g2.dispose();
                super.paintComponent(g);
            }
        };
        box.setOpaque(false);
        box.setPreferredSize(new Dimension(350, 54));
        box.setMaximumSize(new Dimension(360, 54));
        box.setBorder(new EmptyBorder(0, 18, 0, 10));

        box.add(label, BorderLayout.CENTER);

        // Icon sao chép Vector (Hai trang giấy chồng lên nhau)
        JButton btnCopy = createVectorCopyButton(onClickCopy);
        box.add(btnCopy, BorderLayout.EAST);

        return box;
    }

    /**
     * Nút Icon Sao chép Vector vẽ trực tiếp bằng Java2D:
     * - Trạng thái bình thường: Hai tờ giấy màu trắng đè lên nhau.
     * - Trạng thái Hover: Nền mờ bo góc nhẹ.
     * - Khi bấm: Đổi sang dấu tick kiểm xanh trong 1.2s xác nhận đã sao chép.
     */
    private JButton createVectorCopyButton(Runnable onClickCopy) {
        JButton btn = new JButton() {
            private boolean isHover = false;
            private boolean isCopied = false;
            private Timer copyResetTimer;

            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { isHover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { isHover = false; repaint(); }
                });

                addActionListener(e -> {
                    onClickCopy.run();
                    isCopied = true;
                    repaint();

                    if (copyResetTimer != null && copyResetTimer.isRunning()) {
                        copyResetTimer.stop();
                    }
                    copyResetTimer = new Timer(1200, evt -> {
                        isCopied = false;
                        repaint();
                    });
                    copyResetTimer.setRepeats(false);
                    copyResetTimer.start();
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();

                if (isHover) {
                    g2.setColor(new Color(255, 255, 255, 50));
                    g2.fillRoundRect(2, 2, w - 4, h - 4, 8, 8);
                }

                int iconX = (w - 18) / 2;
                int iconY = (h - 18) / 2;

                if (isCopied) {
                    // Vẽ dấu checkmark hoàn tất màu xanh lá tươi
                    g2.setColor(new Color(110, 255, 140));
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(iconX + 3, iconY + 9, iconX + 7, iconY + 13);
                    g2.drawLine(iconX + 7, iconY + 13, iconX + 15, iconY + 5);
                } else {
                    // Vẽ hai tờ giấy vector trắng đè lên nhau
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                    // Tờ giấy phía sau
                    g2.drawRoundRect(iconX + 5, iconY, 11, 13, 3, 3);
                    // Tờ giấy phía trước
                    g2.drawRoundRect(iconX, iconY + 4, 11, 13, 3, 3);
                }

                g2.dispose();
            }
        };

        btn.setPreferredSize(new Dimension(36, 36));
        btn.setMaximumSize(new Dimension(36, 36));
        btn.setOpaque(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Sao chép");
        return btn;
    }

    private JPanel createInputFieldBox(String labelPrefix, JTextField textField) {
        JPanel box = new JPanel(new BorderLayout(14, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = 28;

                g2.setColor(new Color(255, 255, 255, 38));
                g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(2.2f));
                g2.draw(new RoundRectangle2D.Float(1.5f, 1.5f, w - 3, h - 3, arc, arc));

                g2.dispose();
                super.paintComponent(g);
            }
        };
        box.setOpaque(false);
        box.setPreferredSize(new Dimension(350, 54));
        box.setMaximumSize(new Dimension(360, 54));
        box.setBorder(new EmptyBorder(0, 18, 0, 16));

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

    private JButton createPillConnectButton() {
        JButton btn = new JButton("CONNECT") {
            private boolean isHover = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { isHover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { isHover = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                int w = getWidth();
                int h = getHeight();
                int arc = h - 2;

                g2.setColor(isHover ? new Color(242, 246, 252) : Color.WHITE);
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

    // =========================================================================
    // PANEL THƯƠNG HIỆU NỀN TRẮNG (LOGO VÀ NÚT CHUYỂN ĐỔI)
    // =========================================================================

    private class BrandInfoPanel extends JPanel {
        private final JLabel lblBrand;
        private final JButton btnSwitch;
        private final JPanel topLogoPanel;
        private boolean isClientModeState = false;

        public BrandInfoPanel(ActionListener onSwitch) {
            setLayout(new BorderLayout());
            setOpaque(true);
            setBackground(Color.WHITE);
            setBorder(new EmptyBorder(25, 30, 35, 30));

            // Top Bar: Logo HKTNP di chuyển giữa bên Phải (khi Server) và bên Trái (khi Client)
            topLogoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            topLogoPanel.setOpaque(false);
            topLogoPanel.add(new HktnpLogoComponent());
            add(topLogoPanel, BorderLayout.NORTH);

            // Center: Tiêu đề và Nút chuyển
            JPanel centerPanel = new JPanel();
            centerPanel.setOpaque(false);
            centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
            centerPanel.add(Box.createVerticalGlue());

            lblBrand = new JLabel("REMOTE DESKTOP ACCESS");
            lblBrand.setFont(new Font("Segoe UI", Font.BOLD, 22));
            lblBrand.setForeground(BLUE_START);
            lblBrand.setAlignmentX(Component.CENTER_ALIGNMENT);

            btnSwitch = createPillOutlineButton("SWITCH TO CLIENT", BLUE_START);
            btnSwitch.setAlignmentX(Component.CENTER_ALIGNMENT);
            btnSwitch.addActionListener(onSwitch);

            centerPanel.add(lblBrand);
            centerPanel.add(Box.createVerticalStrut(28));
            centerPanel.add(btnSwitch);
            centerPanel.add(Box.createVerticalGlue());

            add(centerPanel, BorderLayout.CENTER);
        }

        public void setMode(boolean isClient) {
            if (this.isClientModeState == isClient) return;
            this.isClientModeState = isClient;

            if (isClient) {
                // Đang ở chế độ Client: BrandPanel nằm bên Trái -> Logo canh Trái, Chữ Cam, Nút SWITCH TO SERVER
                ((FlowLayout) topLogoPanel.getLayout()).setAlignment(FlowLayout.LEFT);
                lblBrand.setForeground(ORANGE_START);
                btnSwitch.setText("SWITCH TO SERVER");
                btnSwitch.setForeground(ORANGE_START);
            } else {
                // Đang ở chế độ Server: BrandPanel nằm bên Phải -> Logo canh Phải, Chữ Xanh, Nút SWITCH TO CLIENT
                ((FlowLayout) topLogoPanel.getLayout()).setAlignment(FlowLayout.RIGHT);
                lblBrand.setForeground(BLUE_START);
                btnSwitch.setText("SWITCH TO CLIENT");
                btnSwitch.setForeground(BLUE_START);
            }
            topLogoPanel.revalidate();
            topLogoPanel.repaint();
            btnSwitch.repaint();
            lblBrand.repaint();
        }

        private JButton createPillOutlineButton(String text, Color primaryColor) {
            JButton btn = new JButton(text) {
                private boolean isHover = false;
                {
                    addMouseListener(new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) { isHover = true; repaint(); }
                        @Override public void mouseExited(MouseEvent e) { isHover = false; repaint(); }
                    });
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    int w = getWidth();
                    int h = getHeight();
                    int arc = h - 2;

                    Color curCol = isClientModeState ? ORANGE_START : BLUE_START;

                    if (isHover) {
                        g2.setColor(new Color(curCol.getRed(), curCol.getGreen(), curCol.getBlue(), 28));
                        g2.fill(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));
                    }

                    g2.setColor(curCol);
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
    }

    private void copyToClipboard(String content, String notifyMsg) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(content), null);
    }

    private Color interpolateColor(Color c1, Color c2, float ratio) {
        float r = c1.getRed()   + (c2.getRed()   - c1.getRed())   * ratio;
        float g = c1.getGreen() + (c2.getGreen() - c1.getGreen()) * ratio;
        float b = c1.getBlue()  + (c2.getBlue()  - c1.getBlue())  * ratio;
        return new Color((int) Math.min(255, Math.max(0, r)),
                         (int) Math.min(255, Math.max(0, g)),
                         (int) Math.min(255, Math.max(0, b)));
    }

    // =========================================================================
    // XỬ LÝ KẾT NỐI CLIENT -> SERVER
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

        final java.util.concurrent.atomic.AtomicBoolean authFailedFlag = new java.util.concurrent.atomic.AtomicBoolean(false);

        SwingWorker<ConnectionManager, String> worker = new SwingWorker<>() {
            private String errorMessage = null;

            @Override
            protected ConnectionManager doInBackground() {
                publish("Đang tìm máy Server trên mạng...");

                // 1. Nếu đã nhận dạng qua UDP broadcast
                if (discoveredServers.containsKey(reqSessionId)) {
                    String host = discoveredServers.get(reqSessionId);
                    publish("Đã tìm thấy Server (" + host + "), đang kết nối...");
                    ConnectionManager conn = tryConnect(host, reqSessionId, reqPasscode, authFailedFlag);
                    if (conn != null) return conn;
                }

                // 2. Thử các máy chủ vừa phát hiện trong discoveredServers
                for (String host : discoveredServers.values()) {
                    publish("Đang thử kết nối tới " + host + "...");
                    ConnectionManager conn = tryConnect(host, reqSessionId, reqPasscode, authFailedFlag);
                    if (conn != null) return conn;
                }

                // 3. Nếu người dùng nhập trực tiếp IP (hoặc ID@IP)
                if (inputId.contains(".")) {
                    String host = inputId;
                    String sId = reqSessionId;
                    if (inputId.contains("@")) {
                        String[] p = inputId.split("@", 2);
                        sId = p[0].trim().toUpperCase();
                        host = p[1].trim();
                    }
                    publish("Đang kết nối tới " + host + "...");
                    ConnectionManager connDirect = tryConnect(host, sId, reqPasscode, authFailedFlag);
                    if (connDirect != null) return connDirect;
                }

                // 4. Thử localhost nếu chạy trên cùng một máy
                publish("Đang thử kết nối cục bộ (127.0.0.1)...");
                ConnectionManager connLocal = tryConnect("127.0.0.1", reqSessionId, reqPasscode, authFailedFlag);
                if (connLocal != null) return connLocal;

                // 5. Tự động dò quét dải mạng LAN để tìm Server
                publish("Đang quét tự động mạng LAN tìm máy Server (Port 5002)...");
                List<String> candidateIps = findSubnetCandidateIps();
                for (String candIp : candidateIps) {
                    publish("Đang thử kết nối máy " + candIp + "...");
                    ConnectionManager c = tryConnect(candIp, reqSessionId, reqPasscode, authFailedFlag);
                    if (c != null) {
                        discoveredServers.put(reqSessionId, candIp);
                        return c;
                    }
                }

                if (authFailedFlag.get()) {
                    errorMessage = "Sai ID hoặc Passcode! Server đã nhận diện nhưng từ chối đăng nhập.";
                } else {
                    errorMessage = "Không tìm thấy Server khớp với ID [" + inputId + "] trên mạng!";
                }
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

                        RemoteViewFrame viewFrame = new RemoteViewFrame(connection, () -> {
                            MainFrame.this.setVisible(true);
                        });
                        viewFrame.setVisible(true);
                        viewFrame.startReceiving();

                        MainFrame.this.setVisible(false);
                    } else {
                        if (authFailedFlag.get()) {
                            lblClientStatus.setText("Sai ID hoặc Passcode! Kiểm tra lại mã ở thẻ Server.");
                            lblClientStatus.setForeground(new Color(255, 120, 120));
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    "Đăng nhập thất bại: Sai ID hoặc Passcode!\n\n" +
                                    "💡 Lưu ý:\n" +
                                    "- Máy Server đã phản hồi nhưng ID hoặc Passcode không khớp.\n" +
                                    "- Mỗi lần ứng dụng khởi động lại, ID và Passcode sẽ được tạo mới hoàn toàn.\n" +
                                    "- Nhấn 'SWITCH TO SERVER' bên cạnh để lấy đúng ID và Passcode (gồm 6 số) hiện tại.",
                                    "Sai thông tin đăng nhập", JOptionPane.WARNING_MESSAGE);
                        } else {
                            lblClientStatus.setText(errorMessage != null ? errorMessage : "Kết nối thất bại! Kiểm tra ID và Passcode.");
                            lblClientStatus.setForeground(new Color(255, 120, 120));
                            JOptionPane.showMessageDialog(MainFrame.this,
                                    (errorMessage != null ? errorMessage : "Kết nối thất bại!") +
                                    "\n\n💡 Gợi ý:\n- Đảm bảo máy Server đã bật và mở port 5002 (Control) và 5001 (Video).\n- Đảm bảo 2 máy kết nối chung Wi-Fi hoặc ZeroTier.\n- Có thể nhập trực tiếp IP của máy Server vào ô ID.",
                                    "Kết nối thất bại", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                } catch (Exception e) {
                    lblClientStatus.setText("Lỗi: " + e.getMessage());
                    lblClientStatus.setForeground(new Color(255, 120, 120));
                }
            }
        };

        worker.execute();
    }

    /**
     * Phase 5 — Dual-socket tryConnect.
     * Connects control channel, authenticates (parses "OK|token" from AUTH_RESP),
     * then opens the video channel with the received stream token.
     */
    private ConnectionManager tryConnect(String host, String sessionId, String passcode,
                                         java.util.concurrent.atomic.AtomicBoolean authFailedFlag) {
        if (host == null || host.trim().isEmpty()) return null;
        try {
            ConnectionManager conn = new ConnectionManager(
                    host.trim(),
                    com.remote.common.network.NetworkConstants.DEFAULT_CONTROL_PORT,
                    com.remote.common.network.NetworkConstants.DEFAULT_VIDEO_PORT);
            conn.connectControl();

            DataInputStream  dis = conn.getControlInputStream();
            DataOutputStream dos = conn.getControlOutputStream();

            // Send AUTH_REQ
            String authPayload = sessionId + "|" + passcode;
            byte[] payloadBytes = authPayload.getBytes(StandardCharsets.UTF_8);
            PacketBuilder.write(dos, PacketType.AUTH_REQ, payloadBytes);

            // Read AUTH_RESP: "OK|streamToken" or "FAIL" or legacy byte
            PacketParser.Packet resp = PacketParser.read(dis);
            if (resp.type() != PacketType.AUTH_RESP) {
                conn.disconnect();
                return null;
            }

            String body = resp.payloadAsString();
            boolean authOk = false;
            String streamToken = "";

            if (body != null && body.startsWith("OK|")) {
                authOk = true;
                streamToken = body.substring(3);
            } else if (resp.payload().length > 0 && resp.payload()[0] == 1) {
                // Backward-compat: legacy 1-byte success (no token)
                authOk = true;
            }

            if (!authOk) {
                if (authFailedFlag != null) authFailedFlag.set(true);
                conn.disconnect();
                return null;
            }

            // Pair Video Channel if we received a token
            if (!streamToken.isEmpty()) {
                try {
                    conn.connectVideo(sessionId, streamToken);
                } catch (Exception e) {
                    System.err.println("[MainFrame] Video pairing failed: " + e.getMessage());
                    // Connection still valid for control-only legacy mode
                }
            }

            return conn;

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
                                        s.connect(new InetSocketAddress(targetIp,
                                                com.remote.common.network.NetworkConstants.DEFAULT_CONTROL_PORT), 350);
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
