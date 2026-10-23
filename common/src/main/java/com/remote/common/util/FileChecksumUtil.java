package com.remote.common.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * Tiện ích tính toán Checksum (CRC32) để kiểm soát tính toàn vẹn của tệp tin khi truyền qua mạng.
 * Đảm bảo file nhận được không bị lỗi byte hoặc rách gói tin.
 */
public final class FileChecksumUtil {

    private FileChecksumUtil() {}

    /**
     * Tính mã CRC32 dạng Hex của file (ví dụ: "A1B2C3D4")
     */
    public static String calculateCRC32(File file) throws IOException {
        if (file == null || !file.exists() || !file.isFile()) {
            return "00000000";
        }

        CRC32 crc = new CRC32();
        byte[] buffer = new byte[16384]; // 16KB buffer
        try (FileInputStream fis = new FileInputStream(file)) {
            int len;
            while ((len = fis.read(buffer)) != -1) {
                crc.update(buffer, 0, len);
            }
        }
        return Long.toHexString(crc.getValue()).toUpperCase();
    }

    /**
     * Kiểm tra tính toàn vẹn của file so với mã checksum mong đợi
     */
    public static boolean verifyChecksum(File file, String expectedChecksum) {
        if (expectedChecksum == null || expectedChecksum.trim().isEmpty() || "NONE".equalsIgnoreCase(expectedChecksum)) {
            return true; // Bỏ qua nếu không yêu cầu kiểm tra checksum
        }
        try {
            String actual = calculateCRC32(file);
            return actual.equalsIgnoreCase(expectedChecksum.trim());
        } catch (Exception e) {
            return false;
        }
    }
}
