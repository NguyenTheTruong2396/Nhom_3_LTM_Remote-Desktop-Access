package com.remote.common.model;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Đại diện cho thông tin một tệp tin hoặc thư mục khi duyệt cây thư mục từ xa (Remote Directory Browsing).
 */
public class RemoteFileInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String path;
    private final boolean isDirectory;
    private final long size;
    private final long lastModified;

    public RemoteFileInfo(String name, String path, boolean isDirectory, long size, long lastModified) {
        this.name = name;
        this.path = path;
        this.isDirectory = isDirectory;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public String getFormattedSize() {
        if (isDirectory) return "<Thư mục>";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024.0));
        return String.format("%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    public String getFormattedDate() {
        if (lastModified <= 0) return "--";
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        return sdf.format(new Date(lastModified));
    }

    /**
     * Chuyển đổi thành chuỗi TSV (Tab Separated Values) để đóng gói truyền qua TLV packet
     */
    public String toTsvLine() {
        return (isDirectory ? "1" : "0") + "\t"
                + name.replace("\t", " ") + "\t"
                + size + "\t"
                + lastModified + "\t"
                + path.replace("\t", " ");
    }

    /**
     * Giải mã từ chuỗi TSV
     */
    public static RemoteFileInfo fromTsvLine(String line) {
        if (line == null || line.trim().isEmpty()) return null;
        String[] parts = line.split("\t", 5);
        if (parts.length < 5) return null;
        try {
            boolean isDir = "1".equals(parts[0]);
            String name = parts[1];
            long size = Long.parseLong(parts[2]);
            long lastMod = Long.parseLong(parts[3]);
            String path = parts[4];
            return new RemoteFileInfo(name, path, isDir, size, lastMod);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return (isDirectory ? "[DIR] " : "[FILE] ") + name + " (" + getFormattedSize() + ")";
    }
}
