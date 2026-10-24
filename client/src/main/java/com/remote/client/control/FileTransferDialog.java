package com.remote.client.control;

import com.remote.client.network.FileTransferService;
import com.remote.client.network.FileTransferService.FileTransferCallback;
import com.remote.client.network.FileTransferService.RemoteDirectoryCallback;
import com.remote.common.model.RemoteFileInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Giao diện Quản lý & Truyền Tệp Tin Hai Chiều (Bidirectional File Transfer Manager):
 * - Bảng Trái: Duyệt tệp máy cục bộ (Client).
 * - Bảng Phải: Duyệt tệp máy điều khiển từ xa (Server) qua Remote Directory Browsing.
 * - Nút "Gửi lên >>": Upload file Client -> Server.
 * - Nút "<< Lấy về": Tải file từ Server về Client (Download 2 chiều).
 * - Loại bỏ hoàn toàn Emoji Unicode tránh lỗi ô vuông [ ] trên Windows Swing.
 * - Tự vẽ component ModernButton chống lỗi giao diện Windows trắng xóa đè màu chữ.
 */
public class FileTransferDialog extends JDialog {

    private final FileTransferService transferService;

    // Component bảng Client (Local)
    private JTextField txtLocalPath;
    private JTable tblLocal;
    private DefaultTableModel modelLocal;
    private File currentLocalDir;
    private boolean isLocalRootsMode = false;
    private final List<File> localFilesList = new ArrayList<>();

    // Component bảng Server (Remote)
    private JTextField txtRemotePath;
    private JTable tblRemote;
    private DefaultTableModel modelRemote;
    private String currentRemotePath = "";
    private final List<RemoteFileInfo> remoteFilesList = new ArrayList<>();

    // Thanh trạng thái & tiến trình
    private JProgressBar progressBar;
    private JLabel lblStatus;
    private ModernButton btnCancel;
    private ModernButton btnUpload;
    private ModernButton btnDownload;

    // Bảng màu giao diện tối hiện đại
    private static final Color BG_DARK = new Color(0x0F, 0x17, 0x2A);
    private static final Color BG_PANEL = new Color(0x1E, 0x29, 0x3B);
    private static final Color BG_ROW_ALT = new Color(0x15, 0x20, 0x33);
    private static final Color ACCENT_BLUE = new Color(0x1B, 0x75, 0xBC);
    private static final Color ACCENT_BLUE_HOVER = new Color(0x28, 0x8C, 0xDB);
    private static final Color ACCENT_ORANGE = new Color(0xEA, 0x58, 0x0C);
    private static final Color ACCENT_ORANGE_HOVER = new Color(0xF9, 0x73, 0x16);
    private static final Color TEXT_WHITE = new Color(241, 245, 249);
    private static final Color TEXT_MUTED = new Color(148, 163, 184);
    private static final Color TEXT_CYAN = new Color(56, 189, 248);

    public FileTransferDialog(Frame owner, FileTransferService transferService) {
        super(owner, "Quản lý & Truyền Tệp Tin Hai Chiều (Client <-> Server)", false);
        this.transferService = transferService;

        setSize(1080, 650);
        setMinimumSize(new Dimension(900, 550));
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        buildUI();

        // Tải danh sách ổ đĩa cục bộ Client và danh sách gốc từ Server
        loadLocalRoots();
        refreshRemoteDirectory(""); // Yêu cầu danh sách gốc từ Server
    }

    private void buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG_DARK);

        // 1. Header Dialog
        root.add(createHeaderPanel(), BorderLayout.NORTH);

        // 2. Nội dung 2 bảng (Client bên trái, Server bên phải, nút giữa)
        root.add(createCenterPanel(), BorderLayout.CENTER);

        // 3. Footer: Tiến trình + Trạng thái
        root.add(createFooterPanel(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(new EmptyBorder(12, 20, 10, 20));

        JLabel lblTitle = new JLabel("TRUYỀN & ĐỒNG BỘ TỆP TIN HAI CHIỀU");
        lblTitle.setFont(new Font("Segoe UI", Font.BOLD, 15));
        lblTitle.setForeground(TEXT_WHITE);

        JLabel lblSub = new JLabel("Client <-> Server  •  Duyệt thư mục & ổ đĩa hai chiều  •  Kiểm tra toàn vẹn Checksum CRC32");
        lblSub.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblSub.setForeground(TEXT_MUTED);

        JPanel p = new JPanel(new GridLayout(2, 1, 0, 3));
        p.setOpaque(false);
        p.add(lblTitle);
        p.add(lblSub);

        header.add(p, BorderLayout.WEST);
        return header;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;

        // Cột trái: Máy Client
        gbc.gridx = 0;
        gbc.weightx = 0.45;
        panel.add(createLocalPane(), gbc);

        // Cụm nút giữa: Gửi sang Server / Lấy về Client
        gbc.gridx = 1;
        gbc.weightx = 0.10;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(createMiddleActionButtons(), gbc);

        // Cột phải: Máy Server
        gbc.gridx = 2;
        gbc.weightx = 0.45;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(createRemotePane(), gbc);

        return panel;
    }

    private JPanel createLocalPane() {
        JPanel pane = new JPanel(new BorderLayout(0, 8));
        pane.setOpaque(false);

        // Tiêu đề
        JLabel title = new JLabel("[CLIENT] MÁY CỦA BẠN");
        title.setFont(new Font("Segoe UI", Font.BOLD, 13));
        title.setForeground(TEXT_CYAN);

        // Thanh điều hướng đường dẫn
        JPanel navBar = new JPanel(new BorderLayout(6, 0));
        navBar.setOpaque(false);

        txtLocalPath = new JTextField();
        txtLocalPath.setEditable(false);
        txtLocalPath.setBackground(BG_PANEL);
        txtLocalPath.setForeground(TEXT_WHITE);
        txtLocalPath.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        txtLocalPath.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 30)),
                new EmptyBorder(5, 8, 5, 8)
        ));

        ModernButton btnUp = new ModernButton("Lên", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnUp.setPreferredSize(new Dimension(54, 28));
        btnUp.setToolTipText("Lên thư mục cha");
        btnUp.addActionListener(e -> {
            if (isLocalRootsMode) return;
            if (currentLocalDir != null) {
                File parent = currentLocalDir.getParentFile();
                if (parent != null) {
                    loadLocalDirectory(parent);
                } else {
                    loadLocalRoots();
                }
            } else {
                loadLocalRoots();
            }
        });

        ModernButton btnRoots = new ModernButton("Ổ đĩa", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnRoots.setPreferredSize(new Dimension(62, 28));
        btnRoots.setToolTipText("Xem danh sách các ổ đĩa máy của bạn");
        btnRoots.addActionListener(e -> loadLocalRoots());

        ModernButton btnRefresh = new ModernButton("Nạp lại", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnRefresh.setPreferredSize(new Dimension(68, 28));
        btnRefresh.setToolTipText("Làm mới danh sách");
        btnRefresh.addActionListener(e -> {
            if (isLocalRootsMode || currentLocalDir == null) {
                loadLocalRoots();
            } else {
                loadLocalDirectory(currentLocalDir);
            }
        });

        JPanel navBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        navBtns.setOpaque(false);
        navBtns.add(btnUp);
        navBtns.add(btnRoots);
        navBtns.add(btnRefresh);

        navBar.add(txtLocalPath, BorderLayout.CENTER);
        navBar.add(navBtns, BorderLayout.EAST);

        // Bảng danh sách file: 4 Cột rõ ràng
        String[] cols = {"Tên tệp tin", "Loại", "Kích thước", "Ngày sửa"};
        modelLocal = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblLocal = createStyledTable(modelLocal);
        tblLocal.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tblLocal.getSelectedRow();
                    if (row >= 0 && row < localFilesList.size()) {
                        File clicked = localFilesList.get(row);
                        if (clicked.isDirectory()) {
                            loadLocalDirectory(clicked);
                        }
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tblLocal);
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 30)));

        JPanel top = new JPanel(new BorderLayout(0, 5));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(navBar, BorderLayout.CENTER);

        pane.add(top, BorderLayout.NORTH);
        pane.add(scroll, BorderLayout.CENTER);

        return pane;
    }

    private JPanel createRemotePane() {
        JPanel pane = new JPanel(new BorderLayout(0, 8));
        pane.setOpaque(false);

        // Tiêu đề
        JLabel title = new JLabel("[SERVER] MÁY ĐIỀU KHIỂN");
        title.setFont(new Font("Segoe UI", Font.BOLD, 13));
        title.setForeground(ACCENT_ORANGE_HOVER);

        // Thanh điều hướng đường dẫn từ xa
        JPanel navBar = new JPanel(new BorderLayout(6, 0));
        navBar.setOpaque(false);

        txtRemotePath = new JTextField();
        txtRemotePath.setEditable(false);
        txtRemotePath.setBackground(BG_PANEL);
        txtRemotePath.setForeground(TEXT_WHITE);
        txtRemotePath.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        txtRemotePath.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 30)),
                new EmptyBorder(5, 8, 5, 8)
        ));

        ModernButton btnUp = new ModernButton("Lên", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnUp.setPreferredSize(new Dimension(54, 28));
        btnUp.setToolTipText("Lên thư mục cha");
        btnUp.addActionListener(e -> {
            if (currentRemotePath != null && !currentRemotePath.isEmpty() && !"ROOTS".equals(currentRemotePath)) {
                File f = new File(currentRemotePath);
                String parent = f.getParent();
                refreshRemoteDirectory(parent != null ? parent : "ROOTS");
            }
        });

        ModernButton btnRoots = new ModernButton("Ổ đĩa", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnRoots.setPreferredSize(new Dimension(62, 28));
        btnRoots.setToolTipText("Xem danh sách các ổ đĩa");
        btnRoots.addActionListener(e -> refreshRemoteDirectory("ROOTS"));

        ModernButton btnRefresh = new ModernButton("Nạp lại", new Color(0x33, 0x41, 0x55), new Color(0x47, 0x55, 0x69));
        btnRefresh.setPreferredSize(new Dimension(68, 28));
        btnRefresh.setToolTipText("Làm mới danh sách");
        btnRefresh.addActionListener(e -> refreshRemoteDirectory(currentRemotePath));

        JPanel navBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        navBtns.setOpaque(false);
        navBtns.add(btnUp);
        navBtns.add(btnRoots);
        navBtns.add(btnRefresh);

        navBar.add(txtRemotePath, BorderLayout.CENTER);
        navBar.add(navBtns, BorderLayout.EAST);

        // Bảng danh sách file từ xa
        String[] cols = {"Tên tệp tin", "Loại", "Kích thước", "Ngày sửa"};
        modelRemote = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };

        tblRemote = createStyledTable(modelRemote);
        tblRemote.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = tblRemote.getSelectedRow();
                    if (row >= 0 && row < remoteFilesList.size()) {
                        RemoteFileInfo info = remoteFilesList.get(row);
                        if (info.isDirectory()) {
                            refreshRemoteDirectory(info.getPath());
                        }
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tblRemote);
        scroll.getViewport().setBackground(BG_PANEL);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 30)));

        JPanel top = new JPanel(new BorderLayout(0, 5));
        top.setOpaque(false);
        top.add(title, BorderLayout.NORTH);
        top.add(navBar, BorderLayout.CENTER);

        pane.add(top, BorderLayout.NORTH);
        pane.add(scroll, BorderLayout.CENTER);

        return pane;
    }

    private JPanel createMiddleActionButtons() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 10, 0, 10));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.insets = new Insets(10, 0, 10, 0);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        btnUpload = new ModernButton("Gửi lên >>", ACCENT_BLUE, ACCENT_BLUE_HOVER);
        btnUpload.setPreferredSize(new Dimension(108, 38));
        btnUpload.setToolTipText("Tải tệp đang chọn từ Client lên Server");
        btnUpload.addActionListener(e -> handleUpload());

        btnDownload = new ModernButton("<< Lấy về", ACCENT_ORANGE, ACCENT_ORANGE_HOVER);
        btnDownload.setPreferredSize(new Dimension(108, 38));
        btnDownload.setToolTipText("Lấy tệp đang chọn từ Server tải về máy Client");
        btnDownload.addActionListener(e -> handleDownload());

        gbc.gridy = 0;
        p.add(btnUpload, gbc);

        gbc.gridy = 1;
        p.add(btnDownload, gbc);

        return p;
    }

    private JPanel createFooterPanel() {
        JPanel footer = new JPanel(new BorderLayout(10, 8));
        footer.setOpaque(false);
        footer.setBorder(new EmptyBorder(10, 20, 16, 20));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setForeground(new Color(0x38, 0xBD, 0xF8));
        progressBar.setBackground(BG_PANEL);
        progressBar.setBorder(BorderFactory.createLineBorder(new Color(255, 255, 255, 30)));
        progressBar.setPreferredSize(new Dimension(100, 22));

        lblStatus = new JLabel("Sẵn sàng truyền nhận (Đã bật kiểm tra tính toàn vẹn CRC32)");
        lblStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        lblStatus.setForeground(TEXT_MUTED);

        btnCancel = new ModernButton("Hủy truyền", new Color(0xDC, 0x26, 0x26), new Color(0xEF, 0x44, 0x44));
        btnCancel.setPreferredSize(new Dimension(105, 28));
        btnCancel.setEnabled(false);
        btnCancel.addActionListener(e -> {
            if (transferService != null) {
                transferService.cancelTransfer();
                lblStatus.setText("Đang yêu cầu hủy truyền file...");
            }
        });

        JPanel progressRow = new JPanel(new BorderLayout(10, 0));
        progressRow.setOpaque(false);
        progressRow.add(progressBar, BorderLayout.CENTER);
        progressRow.add(btnCancel, BorderLayout.EAST);

        footer.add(lblStatus, BorderLayout.NORTH);
        footer.add(progressRow, BorderLayout.SOUTH);

        return footer;
    }

    // =========================================================================
    // XỬ LÝ SỰ KIỆN UPLOAD & DOWNLOAD
    // =========================================================================

    private void handleUpload() {
        int selRow = tblLocal.getSelectedRow();
        if (selRow < 0 || selRow >= localFilesList.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một tệp tin bên bảng Client để gửi lên Server!",
                    "Chưa chọn tệp tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File fileToUpload = localFilesList.get(selRow);
        if (fileToUpload.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn tệp tin (nhấp đúp để mở thư mục)!",
                    "Không thể gửi thư mục", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setTransferringUI(true);
        lblStatus.setText("Đang chuẩn bị gửi: " + fileToUpload.getName() + "...");

        transferService.uploadFile(fileToUpload, currentRemotePath, new FileTransferCallback() {
            @Override
            public void onProgress(int percentage, long bytesTransferred, long totalBytes) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(percentage);
                    lblStatus.setText(String.format("Đang tải lên: %s — %d%% (%s / %s)",
                            fileToUpload.getName(), percentage, formatBytes(bytesTransferred), formatBytes(totalBytes)));
                });
            }

            @Override
            public void onSuccess(String message) {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    progressBar.setValue(100);
                    lblStatus.setText(message);
                    JOptionPane.showMessageDialog(FileTransferDialog.this, message, "Hoàn tất tải lên", JOptionPane.INFORMATION_MESSAGE);
                    refreshRemoteDirectory(currentRemotePath);
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    lblStatus.setText("Lỗi: " + error);
                    JOptionPane.showMessageDialog(FileTransferDialog.this, error, "Lỗi tải lên", JOptionPane.ERROR_MESSAGE);
                });
            }

            @Override
            public void onCancel() {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    lblStatus.setText("Đã hủy truyền file.");
                });
            }
        });
    }

    private void handleDownload() {
        int selRow = tblRemote.getSelectedRow();
        if (selRow < 0 || selRow >= remoteFilesList.size()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn một tệp tin bên bảng Server để tải về Client!",
                    "Chưa chọn tệp tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (currentLocalDir == null || isLocalRootsMode) {
            JOptionPane.showMessageDialog(this,
                    "Vui lòng nhấp đúp chọn một ổ đĩa hoặc thư mục cụ thể bên Client để lưu tệp tải về (không thể lưu trực tiếp vào danh sách ổ đĩa)!",
                    "Chưa chọn thư mục lưu", JOptionPane.WARNING_MESSAGE);
            return;
        }

        RemoteFileInfo fileToDownload = remoteFilesList.get(selRow);
        if (fileToDownload.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Vui lòng chọn tệp tin (nhấp đúp để mở thư mục)!",
                    "Không thể tải thư mục", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setTransferringUI(true);
        lblStatus.setText("Đang yêu cầu Server gửi tệp: " + fileToDownload.getName() + "...");

        transferService.downloadFile(fileToDownload.getPath(), currentLocalDir, new FileTransferCallback() {
            @Override
            public void onProgress(int percentage, long bytesTransferred, long totalBytes) {
                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(percentage);
                    lblStatus.setText(String.format("Đang tải về: %s — %d%% (%s / %s)",
                            fileToDownload.getName(), percentage, formatBytes(bytesTransferred), formatBytes(totalBytes)));
                });
            }

            @Override
            public void onSuccess(String message) {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    progressBar.setValue(100);
                    lblStatus.setText(message);
                    JOptionPane.showMessageDialog(FileTransferDialog.this, message, "Hoàn tất tải về", JOptionPane.INFORMATION_MESSAGE);
                    if (currentLocalDir != null) loadLocalDirectory(currentLocalDir);
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    lblStatus.setText("Lỗi: " + error);
                    JOptionPane.showMessageDialog(FileTransferDialog.this, error, "Lỗi tải về", JOptionPane.ERROR_MESSAGE);
                });
            }

            @Override
            public void onCancel() {
                SwingUtilities.invokeLater(() -> {
                    setTransferringUI(false);
                    lblStatus.setText("Đã hủy tải file về.");
                });
            }
        });
    }

    private void setTransferringUI(boolean isBusy) {
        btnCancel.setEnabled(isBusy);
        btnUpload.setEnabled(!isBusy);
        btnDownload.setEnabled(!isBusy);
        if (isBusy) {
            progressBar.setValue(0);
        }
    }

    // =========================================================================
    // NẠP DỮ LIỆU CÂY THƯ MỤC & Ổ ĐĨA
    // =========================================================================

    /**
     * Nạp danh sách các ổ đĩa của máy Client (C:\, D:\, ...)
     */
    private void loadLocalRoots() {
        this.isLocalRootsMode = true;
        this.currentLocalDir = null;
        txtLocalPath.setText("Danh sách ổ đĩa");

        modelLocal.setRowCount(0);
        localFilesList.clear();

        File[] roots = File.listRoots();
        if (roots != null) {
            for (File r : roots) {
                localFilesList.add(r);
                String name = r.getAbsolutePath();
                String typeStr = "Thư mục";
                String sizeStr = "<Thư mục>";
                String dateStr = "--";
                modelLocal.addRow(new Object[]{name, typeStr, sizeStr, dateStr});
            }
        }
        lblStatus.setText("Đã nạp danh sách ổ đĩa Client (" + (roots != null ? roots.length : 0) + " ổ đĩa).");
    }

    private void loadLocalDirectory(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            loadLocalRoots();
            return;
        }
        this.isLocalRootsMode = false;
        this.currentLocalDir = dir;
        txtLocalPath.setText(dir.getAbsolutePath());

        modelLocal.setRowCount(0);
        localFilesList.clear();

        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            });

            SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm");
            for (File f : files) {
                localFilesList.add(f);
                String typeStr = f.isDirectory() ? "Thư mục" : "Tệp tin";
                String sizeStr = f.isDirectory() ? "--" : formatBytes(f.length());
                String dateStr = sdf.format(new Date(f.lastModified()));
                modelLocal.addRow(new Object[]{f.getName(), typeStr, sizeStr, dateStr});
            }
        }
    }

    public void refreshRemoteDirectory(String remotePath) {
        if (transferService == null) return;
        lblStatus.setText("Đang đọc cây thư mục từ Server: " + (remotePath.isEmpty() ? "Gốc" : remotePath) + "...");

        transferService.listRemoteFiles(remotePath, new RemoteDirectoryCallback() {
            @Override
            public void onDirectoryLoaded(String path, List<RemoteFileInfo> items) {
                SwingUtilities.invokeLater(() -> {
                    currentRemotePath = path;
                    txtRemotePath.setText("ROOTS".equals(path) ? "Danh sách ổ đĩa" : path);

                    modelRemote.setRowCount(0);
                    remoteFilesList.clear();

                    for (RemoteFileInfo info : items) {
                        remoteFilesList.add(info);
                        String typeStr = info.isDirectory() ? "Thư mục" : "Tệp tin";
                        modelRemote.addRow(new Object[]{info.getName(), typeStr, info.getFormattedSize(), info.getFormattedDate()});
                    }
                    lblStatus.setText("Đã nạp " + items.size() + " mục từ Server.");
                });
            }

            @Override
            public void onError(String error) {
                SwingUtilities.invokeLater(() -> lblStatus.setText("Lỗi duyệt thư mục: " + error));
            }
        });
    }

    // =========================================================================
    // CÁC HÀM TIỆN ÍCH GIAO DIỆN & TỰ VẼ NÚT BẤM (CHỐNG LỖI LOOK AND FEEL)
    // =========================================================================

    private JTable createStyledTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setBackground(BG_PANEL);
        table.setForeground(TEXT_WHITE);
        table.setSelectionBackground(new Color(0x25, 0x63, 0xEB));
        table.setSelectionForeground(Color.WHITE);
        table.setRowHeight(26);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        // Header bảng: Màu tối viền nổi bật
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                lbl.setOpaque(true);
                lbl.setBackground(new Color(0x33, 0x41, 0x55));
                lbl.setForeground(new Color(0x38, 0xBD, 0xF8));
                lbl.setFont(new Font("Segoe UI", Font.BOLD, 12));
                lbl.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(255, 255, 255, 20)),
                        new EmptyBorder(5, 8, 5, 8)
                ));
                if (col == 2) lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                else if (col == 1 || col == 3) lbl.setHorizontalAlignment(SwingConstants.CENTER);
                else lbl.setHorizontalAlignment(SwingConstants.LEFT);
                return lbl;
            }
        });

        // Cell Renderer tùy biến theo cột:
        // Cột 0: Tên file
        // Cột 1: Loại (Thư mục màu Cam, Tệp tin màu Xanh nhạt)
        // Cột 2: Dung lượng (căn phải)
        // Cột 3: Ngày sửa (căn giữa)
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                lbl.setOpaque(true);

                if (isSelected) {
                    lbl.setBackground(tbl.getSelectionBackground());
                    lbl.setForeground(Color.WHITE);
                } else {
                    lbl.setBackground(row % 2 == 0 ? BG_PANEL : BG_ROW_ALT);
                    lbl.setForeground(TEXT_WHITE);

                    if (col == 1) {
                        // Cột Loại: Làm nổi bật thư mục
                        String type = String.valueOf(value);
                        if ("Thư mục".equals(type)) {
                            lbl.setForeground(new Color(0xFB, 0x92, 0x3C)); // Cam ấm
                        } else {
                            lbl.setForeground(new Color(0x94, 0xA3, 0xB8)); // Xám sáng
                        }
                    }
                }

                lbl.setBorder(new EmptyBorder(0, 8, 0, 8));
                if (col == 2) lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                else if (col == 1 || col == 3) lbl.setHorizontalAlignment(SwingConstants.CENTER);
                else lbl.setHorizontalAlignment(SwingConstants.LEFT);

                return lbl;
            }
        });

        table.getColumnModel().getColumn(0).setPreferredWidth(190);
        table.getColumnModel().getColumn(1).setPreferredWidth(65);
        table.getColumnModel().getColumn(2).setPreferredWidth(85);
        table.getColumnModel().getColumn(3).setPreferredWidth(125);

        return table;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /**
     * Nút bấm hiện đại tự vẽ giao diện (Custom ModernButton)
     * Đảm bảo không bị Windows Look & Feel biến thành màu trắng xóa che mất chữ.
     */
    public static class ModernButton extends JButton {
        private final Color baseColor;
        private final Color hoverColor;
        private boolean isHover = false;

        public ModernButton(String text, Color baseColor, Color hoverColor) {
            super(text);
            this.baseColor = baseColor;
            this.hoverColor = hoverColor;
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(new Font("Segoe UI", Font.BOLD, 12));
            setCursor(new Cursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { isHover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { isHover = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            Color bg;
            if (!isEnabled()) {
                bg = new Color(51, 65, 85);
            } else {
                bg = isHover ? hoverColor : baseColor;
            }

            // Vẽ nền bo góc 8px
            g2.setColor(bg);
            g2.fillRoundRect(0, 0, w, h, 8, 8);

            // Viền nhẹ
            g2.setColor(new Color(255, 255, 255, isEnabled() ? 40 : 15));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

            // Vẽ text rõ ràng ở tâm
            g2.setColor(isEnabled() ? getForeground() : new Color(148, 163, 184));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int textX = (w - fm.stringWidth(getText())) / 2;
            int textY = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(getText(), textX, textY);

            g2.dispose();
        }
    }
}
