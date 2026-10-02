package com.remote.client.ui;

import com.remote.client.core.ClientSocketManager;

import javax.swing.*;
import java.awt.*;

public class ConnectFrame extends JFrame {
    private JTextField txtIpHost;
    private JTextField txtPort;
    private JTextField txtSessionId;
    private JPasswordField txtPasscode;
    private JButton btnConnect;
    private JLabel lblStatus;

    public ConnectFrame() {
        initUI();
    }

    private void initUI() {
        setTitle("Remote Desktop Client - Connect");
        setSize(420, 320);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setResizable(false);

        // Main Panel
        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 15, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Title
        JLabel lblTitle = new JLabel("REMOTE DESKTOP CONNECT", SwingConstants.CENTER);
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 16));
        lblTitle.setForeground(new Color(33, 150, 243));
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        mainPanel.add(lblTitle, gbc);

        gbc.gridwidth = 1;

        // Server IP & Port (Grouped)
        gbc.gridx = 0; gbc.gridy = 1;
        mainPanel.add(new JLabel("Server IP / Port:"), gbc);

        JPanel netPanel = new JPanel(new BorderLayout(5, 0));
        txtIpHost = new JTextField("127.0.0.1", 10);
        txtPort = new JTextField("8888", 4);
        netPanel.add(txtIpHost, BorderLayout.CENTER);
        netPanel.add(txtPort, BorderLayout.EAST);
        gbc.gridx = 1; gbc.gridy = 1;
        mainPanel.add(netPanel, gbc);

        // Session ID
        gbc.gridx = 0; gbc.gridy = 2;
        mainPanel.add(new JLabel("Target Session ID:"), gbc);
        txtSessionId = new JTextField();
        gbc.gridx = 1; gbc.gridy = 2;
        mainPanel.add(txtSessionId, gbc);

        // Passcode
        gbc.gridx = 0; gbc.gridy = 3;
        mainPanel.add(new JLabel("Passcode:"), gbc);
        txtPasscode = new JPasswordField();
        gbc.gridx = 1; gbc.gridy = 3;
        mainPanel.add(txtPasscode, gbc);

        // Connect Button
        btnConnect = new JButton("Connect to Host");
        btnConnect.setFont(new Font("Segoe UI", Font.BOLD, 13));
        btnConnect.setBackground(new Color(33, 150, 243));
        btnConnect.setForeground(Color.WHITE);
        btnConnect.setFocusPainted(false);
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2;
        mainPanel.add(btnConnect, gbc);

        // Status Label
        lblStatus = new JLabel("Ready to connect", SwingConstants.CENTER);
        lblStatus.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        lblStatus.setForeground(Color.GRAY);
        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2;
        mainPanel.add(lblStatus, gbc);

        add(mainPanel);

        // Action Listener
        btnConnect.addActionListener(e -> handleConnect());
        txtPasscode.addActionListener(e -> handleConnect());
    }

    private void handleConnect() {
        String ip = txtIpHost.getText().trim();
        String portStr = txtPort.getText().trim();
        String sessionId = txtSessionId.getText().trim();
        String passcode = new String(txtPasscode.getPassword()).trim();

        if (ip.isEmpty() || portStr.isEmpty() || sessionId.isEmpty() || passcode.isEmpty()) {
            setStatus("Please enter all fields!", Color.RED);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            setStatus("Invalid Port number!", Color.RED);
            return;
        }

        // Disable UI when connecting
        setFormEnabled(false);
        setStatus("Connecting & Authenticating...", Color.BLUE);

        // Run network in Background Thread to prevent freezing UI
        new Thread(() -> {
            ClientSocketManager socketManager = ClientSocketManager.getInstance();
            boolean connected = socketManager.connect(ip, port);

            if (!connected) {
                SwingUtilities.invokeLater(() -> {
                    setStatus("Connection failed (Server unreachable)!", Color.RED);
                    setFormEnabled(true);
                });
                return;
            }

            // Send auth packet to Server
            boolean authSuccess = socketManager.authenticate(sessionId, passcode);

            SwingUtilities.invokeLater(() -> {
                if (authSuccess) {
                    setStatus("Authentication Successful! Opening Stream...", new Color(46, 125, 50));
                    
                    // TODO (Tuần 3 - Dev 3): Mở RemoteViewFrame (Render Panel) tại đây
                    JOptionPane.showMessageDialog(this, 
                        "Xác thực thành công với Session: " + sessionId + "\n(Chờ Dev 3 & Dev 4 ghép UI Render ở Tuần 3)", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    setStatus("Invalid Session ID or Passcode!", Color.RED);
                    setFormEnabled(true);
                    socketManager.disconnect();
                }
            });
        }).start();
    }

    private void setStatus(String message, Color color) {
        lblStatus.setText(message);
        lblStatus.setForeground(color);
    }

    private void setFormEnabled(boolean enabled) {
        txtIpHost.setEnabled(enabled);
        txtPort.setEnabled(enabled);
        txtSessionId.setEnabled(enabled);
        txtPasscode.setEnabled(enabled);
        btnConnect.setEnabled(enabled);
    }

    public static void main(String[] args) {
        try {
            // Set giao diện chuẩn theo hệ điều hành (cho đẹp)
            javax.swing.UIManager.setLookAndFeel(javax.swing.UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        javax.swing.SwingUtilities.invokeLater(() -> {
            ConnectFrame frame = new ConnectFrame();
            frame.setVisible(true);
        });
    }
}