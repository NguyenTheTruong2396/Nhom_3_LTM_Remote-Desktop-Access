package com.remote.server.network;

import com.remote.common.model.FramePacket;
import com.remote.common.util.LatestFrameQueue;
import com.remote.server.nativeapi.JniBridge;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketException;
import java.util.Iterator;

/**
 * Lớp VideoStreamSender (Video Stream Sender trong sơ đồ kiến trúc):
 * Đảm nhiệm việc lấy dữ liệu khung hình (ưu tiên từ native-core qua JNI,
 * có cơ chế dự phòng Java Robot), đóng gói thành FramePacket và chiếu
 * liên tục qua TCP Socket tới Client đã kết nối.
 *
 * Đã áp dụng hàng đợi LatestFrameQueue (Drop Frame) để triệt tiêu lag tích tụ.
 */
public class VideoStreamSender implements Runnable {

    private final Socket clientSocket;
    private final DataOutputStream out;
    private volatile boolean running = false;

    // Hai luồng riêng biệt: Chụp (Producer) & Gửi (Consumer)
    private Thread captureThread;
    private Thread sendThread;

    // Hàng đợi giữ duy nhất 1 frame mới nhất (Drop Frame Queue)
    private final LatestFrameQueue<byte[]> frameQueue = new LatestFrameQueue<>();

    // Cấu hình truyền phát
    private int targetFps = 30;
    private int quality = 60; // Mức chất lượng JPEG (1 - 100)

    // Dự phòng Java AWT Robot khi native DLL chưa nạp
    private Robot fallbackRobot;
    private Rectangle screenRect;
    private ImageWriter jpegWriter;
    private ImageWriteParam jpegWriteParam;

    // Reflection cache cho JNI capture method (tránh lỗi biên dịch khi class JniBridge chưa cập nhật method)
    private Method jniCaptureMethod = null;
    private boolean jniChecked = false;

    private boolean nativeModeLogged = false;

    /**
     * Khởi tạo VideoStreamSender cho kết nối Client cụ thể.
     *
     * @param clientSocket Socket đã kết nối với Client
     * @param out          DataOutputStream của Socket
     */
    public VideoStreamSender(Socket clientSocket, DataOutputStream out) {
        this.clientSocket = clientSocket;
        this.out = out;
        JniBridge.loadNativeLibrary();
        initFallbackCapturer();
    }

    /**
     * Khởi tạo nhanh chỉ từ Socket.
     */
    public VideoStreamSender(Socket clientSocket) throws IOException {
        this(clientSocket, new DataOutputStream(clientSocket.getOutputStream()));
    }

    /**
     * Khởi tạo bộ chụp dự phòng (Fallback) bằng Java Robot.
     * Đảm bảo Server vẫn chiếu màn hình hoạt động ngay lập tức kể cả khi
     * file native-core.dll chưa được biên dịch xong.
     */
    private void initFallbackCapturer() {
        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            this.screenRect = new Rectangle(screenSize);
            this.fallbackRobot = new Robot();

            // Cấu hình bộ nén JPEG bộ nhớ đệm
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (writers.hasNext()) {
                this.jpegWriter = writers.next();
                this.jpegWriteParam = jpegWriter.getDefaultWriteParam();
                this.jpegWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                this.jpegWriteParam.setCompressionQuality(quality / 100.0f);
            }
        } catch (AWTException e) {
            System.err.println("[VideoStreamSender] Không thể khởi tạo Java AWT Robot dự phòng: " + e.getMessage());
        }
    }

    /**
     * Bắt đầu luồng chiếu màn hình liên tục tới Client (Khởi chạy 2 luồng độc lập).
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;

        // 1. Luồng Chụp Màn Hình (Producer)
        captureThread = new Thread(this::captureLoop, "VideoStreamCap-" + clientSocket.getRemoteSocketAddress());
        captureThread.setDaemon(true);

        // 2. Luồng Gửi qua Socket (Consumer)
        sendThread = new Thread(this::sendLoop, "VideoStreamSend-" + clientSocket.getRemoteSocketAddress());
        sendThread.setDaemon(true);

        captureThread.start();
        sendThread.start();

        System.out.println("[VideoStreamSender] Bắt đầu phát luồng video tới " + clientSocket.getRemoteSocketAddress()
                + " (Target FPS: " + targetFps + ", Quality: " + quality + ")");
    }

    /**
     * Dừng phát luồng video an toàn.
     */
    public synchronized void stop() {
        running = false;
        frameQueue.clear();

        if (captureThread != null && captureThread.isAlive()) {
            captureThread.interrupt();
        }
        if (sendThread != null && sendThread.isAlive()) {
            sendThread.interrupt();
        }
        System.out.println("[VideoStreamSender] Đã dừng phát luồng video tới " + clientSocket.getRemoteSocketAddress());
    }

    public boolean isRunning() {
        return running && !clientSocket.isClosed();
    }

    public void setTargetFps(int targetFps) {
        this.targetFps = Math.max(1, Math.min(60, targetFps));
    }

    public void setQuality(int quality) {
        this.quality = Math.max(10, Math.min(100, quality));
        if (jpegWriteParam != null) {
            jpegWriteParam.setCompressionQuality(this.quality / 100.0f);
        }
    }

    @Override
    public void run() {
        start();
    }

    /**
     * Vòng lặp Chụp ảnh màn hình liên tục (Producer).
     * Đưa khung hình vào LatestFrameQueue, tự động đè đè nếu luồng Gửi chưa kịp lấy.
     */
    private void captureLoop() {
        while (running && !clientSocket.isClosed()) {
            long frameStart = System.currentTimeMillis();
            long frameIntervalMs = 1000 / targetFps;

            try {
                // 1. Chụp và nén màn hình ra mảng byte JPEG
                byte[] jpegData = captureAndCompress();

                if (jpegData != null && jpegData.length > 0) {
                    // 2. Đẩy vào queue (Chỉ giữ duy nhất 1 frame mới nhất)
                    frameQueue.push(jpegData);
                }

                // 3. Điều tiết tốc độ khung hình chụp (FPS Limiter)
                long frameTime = System.currentTimeMillis() - frameStart;
                long sleepTime = frameIntervalMs - frameTime;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("[VideoStreamSender] Lỗi không mong muốn trong vòng lặp capture: " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }
    }

    /**
     * Vòng lặp Gửi dữ liệu qua TCP Socket (Consumer).
     * Lấy frame mới nhất từ LatestFrameQueue ra và gửi đi.
     */
    private void sendLoop() {
        int frameCount = 0;
        long totalBytesSent = 0;
        long lastFpsLogTime = System.currentTimeMillis();

        while (running && !clientSocket.isClosed()) {
            try {
                // 1. Chờ và lấy frame mới nhất ra khỏi hàng đợi
                byte[] jpegData = frameQueue.take();

                if (jpegData != null && jpegData.length > 0) {
                    // 2. Đóng gói thành FramePacket
                    FramePacket packet = new FramePacket(System.currentTimeMillis(), jpegData);

                    // 3. Đẩy gói tin qua TCP Socket
                    synchronized (out) {
                        packet.writeTo(out);
                    }

                    frameCount++;
                    totalBytesSent += packet.getLength();

                    // 4. Thống kê FPS thực tế và tốc độ truyền mỗi 2 giây
                    long now = System.currentTimeMillis();
                    if (now - lastFpsLogTime >= 2000) {
                        double elapsedSec = (now - lastFpsLogTime) / 1000.0;
                        double actualFps = frameCount / elapsedSec;
                        double kbps = (totalBytesSent * 8.0 / 1024.0) / elapsedSec;

                        System.out.printf("[VideoStreamSender -> %s] FPS: %.1f | Băng thông: %.1f Kbps | Size: %d KB\n",
                                clientSocket.getRemoteSocketAddress(),
                                actualFps,
                                kbps,
                                jpegData.length / 1024);

                        frameCount = 0;
                        totalBytesSent = 0;
                        lastFpsLogTime = now;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (SocketException e) {
                System.out.println("[VideoStreamSender] Client đã ngắt kết nối: " + e.getMessage());
                break;
            } catch (IOException e) {
                System.err.println("[VideoStreamSender] Lỗi IO khi truyền frame: " + e.getMessage());
                break;
            } catch (Exception e) {
                System.err.println("[VideoStreamSender] Lỗi không mong muốn trong vòng lặp send: " + e.getMessage());
                e.printStackTrace();
                break;
            }
        }

        stop();
    }

    /**
     * Lấy mảng byte JPEG của màn hình hiện tại:
     * - Ưu tiên 1: Gọi JNI từ native-core (tốc độ cao GDI C++).
     * - Ưu tiên 2: Sử dụng Java AWT Robot nén JPEG in-memory dự phòng.
     */
    private byte[] captureAndCompress() {
        // Ưu tiên 1: Lấy trực tiếp từ C/C++ native-core qua JNI
        if (JniBridge.isLibraryLoaded()) {
            try {
                byte[] nativeData = JniBridge.captureFrame(quality);
                if (nativeData != null && nativeData.length > 0) {
                    if (!nativeModeLogged) {
                        System.out.println("[VideoStreamSender] >>> ĐANG SỬ DỤNG C/C++ NATIVE-CORE (GDI + STB) ĐỂ CHỤP VÀ NÉN FRAME! <<<");
                        nativeModeLogged = true;
                    }
                    return nativeData;
                }
            } catch (UnsatisfiedLinkError | Exception e) {
                System.err.println("[VideoStreamSender] Lỗi khi gọi JNI: " + e.getMessage() + ", chuyển sang Java Fallback.");
            }
        }

        // Ưu tiên 2: Sử dụng Java Fallback nếu JNI chưa sẵn sàng
        return fallbackCaptureAndCompress();
    }

    /**
     * Chụp màn hình bằng Java Robot và nén JPEG in-memory trong RAM.
     */
    private byte[] fallbackCaptureAndCompress() {
        if (fallbackRobot == null || screenRect == null) {
            return null;
        }

        try {
            BufferedImage screenCapture = fallbackRobot.createScreenCapture(screenRect);
            if (screenCapture == null) {
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (jpegWriter != null) {
                synchronized (jpegWriter) {
                    try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(baos)) {
                        jpegWriter.setOutput(mcios);
                        jpegWriter.write(null, new IIOImage(screenCapture, null, null), jpegWriteParam);
                    }
                }
            } else {
                ImageIO.write(screenCapture, "jpg", baos);
            }

            return baos.toByteArray();
        } catch (Exception e) {
            System.err.println("[VideoStreamSender] Lỗi trong Java fallback capture: " + e.getMessage());
            return null;
        }
    }
}