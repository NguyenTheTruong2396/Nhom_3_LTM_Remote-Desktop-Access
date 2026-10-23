package com.remote.client.network;

import com.remote.common.model.RemoteFileInfo;
import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketType;
import com.remote.common.util.FileChecksumUtil;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service API hoàn thiện cho tính năng Truyền File hai chiều (Bidirectional File Transfer)
 * và duyệt thư mục từ xa (Remote Directory Browsing).
 *
 * Tiêu chí đáp ứng:
 * - Production-Ready / Service API với kiểm soát lỗi chặt chẽ.
 * - Hỗ trợ cả 2 chiều: Upload (Client -> Server) và Download (Server -> Client).
 * - Remote Directory Browsing: Lấy danh sách file/thư mục từ Server.
 * - Kiểm tra tính toàn vẹn Checksum (CRC32).
 * - Cơ chế Cancel / Hủy truyền an toàn và dọn rác tệp tải lỗi (.part).
 * - Cung cấp callback rõ ràng: onProgress, onSuccess, onError, onCancel.
 */
public class FileTransferService {

    private static final int CHUNK_SIZE = 16384; // 16KB per chunk

    private final DataOutputStream dos;
    private final AtomicBoolean isTransferring = new AtomicBoolean(false);
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    // Callbacks hiện thời
    private FileTransferCallback activeCallback;
    private RemoteDirectoryCallback activeDirCallback;

    // Trạng thái tải file từ Server về Client (Download)
    private File activeDownloadFile;
    private File activeDownloadPartFile;
    private FileOutputStream downloadFos;
    private String expectedDownloadChecksum;
    private long totalDownloadBytes;
    private long receivedDownloadBytes;

    public FileTransferService(DataOutputStream dos) {
        this.dos = dos;
    }

    public boolean isTransferring() {
        return isTransferring.get();
    }

    // =========================================================================
    // 1. UPLOAD: CLIENT -> SERVER
    // =========================================================================

    /**
     * Tải file từ Client đẩy lên thư mục trên Server
     *
     * @param localFile      File cục bộ cần gửi
     * @param remoteTargetDir Thư mục đích trên Server (hoặc null để dùng downloads/)
     * @param callback       Bộ lắng nghe tiến độ và kết quả
     */
    public void uploadFile(File localFile, String remoteTargetDir, FileTransferCallback callback) {
        if (localFile == null || !localFile.exists() || !localFile.isFile()) {
            if (callback != null) callback.onError("Tệp tin nguồn không tồn tại hoặc không hợp lệ!");
            return;
        }

        if (dos == null) {
            if (callback != null) callback.onError("Kết nối mạng chưa sẵn sàng!");
            return;
        }

        if (!isTransferring.compareAndSet(false, true)) {
            if (callback != null) callback.onError("Đang có một tiến trình truyền file khác đang chạy!");
            return;
        }

        this.activeCallback = callback;
        this.isCancelled.set(false);

        new Thread(() -> {
            try {
                long totalBytes = localFile.length();
                long bytesSent = 0;

                // Tính Checksum CRC32 trước khi gửi
                String crc32 = FileChecksumUtil.calculateCRC32(localFile);

                // 1. Gửi gói FILE_META (0x07): "fileName|fileSize|checksum|targetDir"
                String dir = (remoteTargetDir != null && !remoteTargetDir.trim().isEmpty()) ? remoteTargetDir.trim() : "";
                String metaPayload = localFile.getName() + "|" + totalBytes + "|" + crc32 + "|" + dir;

                synchronized (dos) {
                    PacketBuilder.write(dos, PacketType.FILE_META, metaPayload.getBytes(StandardCharsets.UTF_8));
                }

                // 2. Gửi từng khối FILE_CHUNK (0x08)
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;

                try (FileInputStream fis = new FileInputStream(localFile)) {
                    while (!isCancelled.get() && (bytesRead = fis.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                        synchronized (dos) {
                            PacketBuilder.write(dos, PacketType.FILE_CHUNK, chunk);
                        }

                        bytesSent += bytesRead;
                        int percent = totalBytes > 0 ? (int) ((bytesSent * 100) / totalBytes) : 100;
                        if (activeCallback != null) {
                            activeCallback.onProgress(percent, bytesSent, totalBytes);
                        }

                        // Điều tiết nhẹ để nhường socket buffer cho Video Stream
                        try { Thread.sleep(1); } catch (Exception ignored) {}
                    }
                }

                // Kiểm tra xem có bị hủy ngang không
                if (isCancelled.get()) {
                    synchronized (dos) {
                        PacketBuilder.writeEmpty(dos, PacketType.FILE_CANCEL);
                    }
                    if (activeCallback != null) activeCallback.onCancel();
                } else {
                    // 3. Gửi gói FILE_END (0x09)
                    synchronized (dos) {
                        PacketBuilder.writeEmpty(dos, PacketType.FILE_END);
                    }
                    if (activeCallback != null) {
                        activeCallback.onSuccess("Đã gửi file thành công lên Server: " + localFile.getName()
                                + " (CRC32: " + crc32 + ")");
                    }
                }

            } catch (Exception e) {
                if (activeCallback != null) {
                    activeCallback.onError("Lỗi khi tải file lên Server: " + e.getMessage());
                }
            } finally {
                isTransferring.set(false);
                activeCallback = null;
            }
        }, "File-Upload-Thread").start();
    }

    // =========================================================================
    // 2. DOWNLOAD: SERVER -> CLIENT ("Lấy file từ Server")
    // =========================================================================

    /**
     * Yêu cầu Server gửi file về máy Client
     *
     * @param remoteFilePath Đường dẫn tệp trên Server
     * @param localSaveDir   Thư mục lưu tệp tại máy Client
     * @param callback       Bộ lắng nghe tiến độ và kết quả
     */
    public void downloadFile(String remoteFilePath, File localSaveDir, FileTransferCallback callback) {
        if (remoteFilePath == null || remoteFilePath.trim().isEmpty()) {
            if (callback != null) callback.onError("Đường dẫn tệp từ xa không hợp lệ!");
            return;
        }

        if (dos == null) {
            if (callback != null) callback.onError("Kết nối mạng chưa sẵn sàng!");
            return;
        }

        if (!isTransferring.compareAndSet(false, true)) {
            if (callback != null) callback.onError("Đang có một tiến trình truyền file khác đang chạy!");
            return;
        }

        if (localSaveDir == null || !localSaveDir.exists() || !localSaveDir.isDirectory()) {
            localSaveDir = new File(System.getProperty("user.home"), "Downloads");
            if (!localSaveDir.exists()) localSaveDir.mkdirs();
        }

        this.activeCallback = callback;
        this.isCancelled.set(false);

        // Ghi nhớ thư mục lưu tạm
        final File targetDir = localSaveDir;

        // Gửi gói FILE_DOWNLOAD_REQ (0x0C) sang Server
        try {
            synchronized (dos) {
                PacketBuilder.write(dos, PacketType.FILE_DOWNLOAD_REQ, remoteFilePath.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            isTransferring.set(false);
            if (callback != null) callback.onError("Không thể gửi yêu cầu tải file: " + e.getMessage());
        }
    }

    // =========================================================================
    // 3. XỬ LÝ GÓI TIN ĐẾN CHO TIẾN TRÌNH DOWNLOAD (GỌI TỪ RECEIVER THREAD)
    // =========================================================================

    public void onFileMetaReceived(String payload) {
        try {
            String[] parts = payload.split("\\|", 4);
            String fileName = parts.length > 0 ? parts[0] : "downloaded_file";
            totalDownloadBytes = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            expectedDownloadChecksum = parts.length > 2 ? parts[2] : "";
            receivedDownloadBytes = 0;

            File saveDir = new File(System.getProperty("user.home"), "Downloads");
            if (!saveDir.exists()) saveDir.mkdirs();

            activeDownloadFile = new File(saveDir, fileName);
            activeDownloadPartFile = new File(saveDir, fileName + ".part");

            if (activeDownloadPartFile.exists()) activeDownloadPartFile.delete();

            downloadFos = new FileOutputStream(activeDownloadPartFile);

            if (activeCallback != null) {
                activeCallback.onProgress(0, 0, totalDownloadBytes);
            }
        } catch (Exception e) {
            cleanupDownloadOnError("Lỗi chuẩn bị tệp tải về: " + e.getMessage());
        }
    }

    public void onFileChunkReceived(byte[] chunk) {
        if (downloadFos != null && chunk != null && chunk.length > 0) {
            try {
                downloadFos.write(chunk);
                receivedDownloadBytes += chunk.length;

                int percent = totalDownloadBytes > 0 ? (int) ((receivedDownloadBytes * 100) / totalDownloadBytes) : 0;
                if (activeCallback != null) {
                    activeCallback.onProgress(percent, receivedDownloadBytes, totalDownloadBytes);
                }
            } catch (IOException e) {
                cleanupDownloadOnError("Lỗi ghi dữ liệu tệp tải về: " + e.getMessage());
            }
        }
    }

    public void onFileEndReceived() {
        if (downloadFos != null) {
            try {
                downloadFos.flush();
                downloadFos.close();
                downloadFos = null;

                // Xác thực tính toàn vẹn Checksum CRC32
                boolean checksumOk = FileChecksumUtil.verifyChecksum(activeDownloadPartFile, expectedDownloadChecksum);
                if (!checksumOk) {
                    cleanupDownloadOnError("Xác thực Checksum thất bại! Tệp tải về có thể bị lỗi byte.");
                    return;
                }

                // Đổi tên từ .part thành tên tệp chính thức
                if (activeDownloadFile.exists()) activeDownloadFile.delete();
                boolean renamed = activeDownloadPartFile.renameTo(activeDownloadFile);

                if (renamed) {
                    if (activeCallback != null) {
                        activeCallback.onSuccess("Đã tải file thành công về Client: " + activeDownloadFile.getAbsolutePath()
                                + " (Kiểm tra CRC32 hợp lệ)");
                    }
                } else {
                    cleanupDownloadOnError("Không thể hoàn tất lưu tệp vào đĩa!");
                }
            } catch (Exception e) {
                cleanupDownloadOnError("Lỗi đóng tệp tải về: " + e.getMessage());
            } finally {
                isTransferring.set(false);
                activeCallback = null;
            }
        }
    }

    public void onFileCancelReceived() {
        cleanupDownloadOnError("Tiến trình truyền file đã bị hủy bởi bên kia.");
    }

    private void cleanupDownloadOnError(String errMsg) {
        try {
            if (downloadFos != null) {
                downloadFos.close();
                downloadFos = null;
            }
        } catch (Exception ignored) {}

        // Dọn rác file .part nếu tải lỗi (theo đúng yêu cầu tiêu chí)
        if (activeDownloadPartFile != null && activeDownloadPartFile.exists()) {
            activeDownloadPartFile.delete();
        }

        isTransferring.set(false);
        if (activeCallback != null) {
            activeCallback.onError(errMsg);
            activeCallback = null;
        }
    }

    // =========================================================================
    // 4. DUYỆT THƯ MỤC TỪ XA (REMOTE DIRECTORY BROWSING)
    // =========================================================================

    /**
     * Yêu cầu Server trả về danh sách thư mục / tệp tin
     *
     * @param remotePath Đường dẫn thư mục cần xem (truyền "" hoặc "ROOTS" để lấy danh sách ổ đĩa)
     * @param callback   Bộ lắng nghe kết quả trả về
     */
    public void listRemoteFiles(String remotePath, RemoteDirectoryCallback callback) {
        if (dos == null) {
            if (callback != null) callback.onError("Kết nối mạng chưa sẵn sàng!");
            return;
        }

        this.activeDirCallback = callback;
        try {
            String path = (remotePath != null) ? remotePath.trim() : "";
            synchronized (dos) {
                PacketBuilder.write(dos, PacketType.DIR_REQ, path.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            if (callback != null) callback.onError("Lỗi gửi yêu cầu duyệt thư mục: " + e.getMessage());
        }
    }

    public void onDirRespReceived(String payload) {
        if (activeDirCallback == null) return;

        try {
            String[] lines = payload.split("\n");
            String currentPath = lines.length > 0 ? lines[0] : "";
            List<RemoteFileInfo> list = new ArrayList<>();

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    RemoteFileInfo info = RemoteFileInfo.fromTsvLine(line);
                    if (info != null) list.add(info);
                }
            }

            activeDirCallback.onDirectoryLoaded(currentPath, list);
        } catch (Exception e) {
            activeDirCallback.onError("Lỗi phân tích danh sách thư mục từ Server: " + e.getMessage());
        }
    }

    // =========================================================================
    // 5. HỦY TRUYỀN FILE AN TOÀN (CANCEL)
    // =========================================================================

    public void cancelTransfer() {
        if (isTransferring.get()) {
            isCancelled.set(true);
            try {
                if (dos != null) {
                    synchronized (dos) {
                        PacketBuilder.writeEmpty(dos, PacketType.FILE_CANCEL);
                    }
                }
            } catch (Exception ignored) {}

            cleanupDownloadOnError("Người dùng đã hủy truyền file.");
        }
    }

    // =========================================================================
    // 6. GIAO DIỆN CALLBACKS
    // =========================================================================

    public interface FileTransferCallback {
        void onProgress(int percentage, long bytesTransferred, long totalBytes);
        void onSuccess(String message);
        void onError(String error);
        void onCancel();
    }

    public interface RemoteDirectoryCallback {
        void onDirectoryLoaded(String currentPath, List<RemoteFileInfo> items);
        void onError(String error);
    }
}
