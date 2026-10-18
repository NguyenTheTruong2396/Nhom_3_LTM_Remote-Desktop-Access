package com.remote.client.network;

import com.remote.common.network.PacketBuilder;
import com.remote.common.network.PacketType;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class FileSender {
    private static final int CHUNK_SIZE = 8192; // Khối 8KB

    public static void sendFile(File file, DataOutputStream dos, FileProgressCallback callback) {
        if (file == null || !file.exists() || !file.isFile() || dos == null) {
            System.err.println("[FileSender] File hoặc Output Stream không hợp lệ!");
            return;
        }

        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(file)) {
                long totalBytes = file.length();
                long bytesSent = 0;

                // 1. Gửi gói FILE_META (0x07): "fileName|fileSize"
                String metaPayload = file.getName() + "|" + totalBytes;
                synchronized (dos) {
                    PacketBuilder.write(dos, PacketType.FILE_META, metaPayload.getBytes(StandardCharsets.UTF_8));
                }

                // 2. Gửi từng khối FILE_CHUNK (0x08)
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;

                while ((bytesRead = fis.read(buffer)) != -1) {
                    byte[] chunk = new byte[bytesRead];
                    System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                    synchronized (dos) {
                        PacketBuilder.write(dos, PacketType.FILE_CHUNK, chunk);
                    }

                    bytesSent += bytesRead;
                    int progress = (int) ((bytesSent * 100) / totalBytes);
                    if (callback != null) {
                        callback.onProgress(progress, bytesSent, totalBytes);
                    }
                }

                // 3. Gửi gói FILE_END (0x09)
                synchronized (dos) {
                    PacketBuilder.writeEmpty(dos, PacketType.FILE_END);
                }

                System.out.println("[FileSender] Gửi file hoàn tất: " + file.getName());
                if (callback != null) {
                    callback.onComplete();
                }

            } catch (IOException e) {
                System.err.println("[FileSender] Lỗi khi gửi file: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            }
        }, "File-Sender-Thread").start();
    }

    public interface FileProgressCallback {
        void onProgress(int percentage, long bytesSent, long totalBytes);
        void onComplete();
        void onError(Exception e);
    }
}