#include "windows.h"
#include <mmsystem.h>
#include <iostream>
#include <vector>
#include <thread>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <atomic>
#include <mutex>
#include "capture.h"

#pragma comment(lib, "winmm.lib")

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "image.h"

// Callback gom byte nén trực tiếp vào RAM
static void JpegMemoryCallback(void* context, void* data, int size) {
    std::vector<uint8_t>* buffer = (std::vector<uint8_t>*)context;
    auto* bytes = (const uint8_t*)data;
    buffer->insert(buffer->end(), bytes, bytes + size);
}

// Bộ đo xung nhịp chính xác cao của Windows
double GetTimeMs() {
    static LARGE_INTEGER freq;
    static bool init = false;
    if (!init) {
        QueryPerformanceFrequency(&freq);
        init = true;
    }
    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);
    return (double)(counter.QuadPart * 1000.0) / (double)freq.QuadPart;
}

// Biến cờ bắt tín hiệu dừng an toàn khi nhấn Ctrl + C
std::atomic<bool> isRunning(true);
BOOL WINAPI ConsoleHandler(DWORD signal) {
    if (signal == CTRL_C_EVENT || signal == CTRL_CLOSE_EVENT) {
        isRunning = false;
        return TRUE;
    }
    return FALSE;
}

/**
 * HÀM LÕI TASK TUẦN 2: CHỤP VÀ NÉN TRONG BỘ NHỚ RAM (IN-MEMORY)
 * - capturer     : Đối tượng chụp GDI
 * - outputJpeg   : Mảng byte JPEG kết quả để giao cho JNI / Socket
 * - quality      : Chất lượng JPEG (khuyến nghị 55 - 65 cho Remote Desktop)
 */
bool CaptureAndCompressFrame(ScreenCapturer& capturer, 
                             std::vector<uint8_t>& outputJpeg, 
                             int quality = 60) 
{
    int w = capturer.GetWidth();
    int h = capturer.GetHeight();
    if (w == 0 || h == 0) return false;

    // 1. Chụp màn hình GDI siêu tốc (SRCCOPY + con trỏ chuột được cache)
    if (!capturer.Capture(true)) return false;
    const uint8_t* rawPixels = (const uint8_t*)capturer.GetBuffer();
    if (!rawPixels) return false;

    // 2. Nén trực tiếp BGRA -> JPEG đa luồng (Multi-Threaded Turbo DRI)
    if (outputJpeg.capacity() < 512 * 1024) {
        outputJpeg.reserve(512 * 1024);
    }
    outputJpeg.clear();

    stbi_write_jpg_to_func_bgra(JpegMemoryCallback, &outputJpeg, w, h, rawPixels, quality);
    return !outputJpeg.empty();
}

// Quá tải giữ tương thích
bool CaptureAndCompressFrame(ScreenCapturer& capturer, 
                             std::vector<uint8_t>& /*rgbaBuffer*/, 
                             std::vector<uint8_t>& outputJpeg, 
                             int quality = 60) 
{
    return CaptureAndCompressFrame(capturer, outputJpeg, quality);
}

int main(int argc, char* argv[]) {
    // Đăng ký bắt sự kiện Ctrl + C để dọn dẹp tiến trình
    SetConsoleCtrlHandler(ConsoleHandler, TRUE);

    // Kích hoạt độ phân giải hẹn giờ 1ms của Windows Scheduler
    timeBeginPeriod(1);

    // Cấu hình tham số dòng lệnh:
    // Cú pháp: ./capture.exe [target_fps] [quality] [max_frames]
    // Ví dụ:
    //   ./capture.exe 60        -> Khóa 60 FPS chuẩn
    //   ./capture.exe 120       -> Khóa 120 FPS siêu mượt
    //   ./capture.exe 0         -> Chạy hết công suất phần cứng (Unlimited Benchmark)
    int targetFps = 60;
    int quality = 60;
    int maxFrames = 0;

    if (argc > 1) {
        targetFps = atoi(argv[1]);
    }
    if (argc > 2) {
        quality = atoi(argv[2]);
    }
    if (argc > 3) {
        maxFrames = atoi(argv[3]);
    }

    ScreenCapturer capturer;
    int w = capturer.GetWidth();
    int h = capturer.GetHeight();

    if (w == 0 || h == 0) {
        std::cerr << "Loi: Khong the khoi tao bo chup man hinh!\n";
        timeEndPeriod(1);
        return 1;
    }

    std::cout << "========================================================\n";
    std::cout << "  TURBO REAL-TIME CAPTURE & PARALLEL JPEG ENGINE (60-120 FPS)\n";
    std::cout << "========================================================\n";
    std::cout << "Do phan giai    : " << w << " x " << h << "\n";
    std::cout << "Muc tieu FPS    : " << (targetFps > 0 ? std::to_string(targetFps) + " FPS" : "UNLIMITED (Max)") << "\n";
    std::cout << "Chat luong JPEG : " << quality << "%\n";
    std::cout << "Luồng nén CPU   : " << std::thread::hardware_concurrency() << " cores (Parallel DRI Macroblock Rows)\n";
    std::cout << "Tip             : Dung './capture.exe 120' hoac './capture.exe 60'\n";
    std::cout << "Nhan [Ctrl + C] de dung tien trinh.\n\n" << std::flush;

    // Khởi tạo bộ đệm JPEG cố định một lần duy nhất (Buffer Reuse)
    std::vector<uint8_t> jpegBuffer;
    jpegBuffer.reserve(512 * 1024);

    // Luồng nền lưu file live_stream.jpg bất đồng bộ (Zero-stalling disk I/O)
    std::vector<uint8_t> previewBuffer;
    std::mutex previewMutex;
    std::atomic<bool> previewReady(false);

    std::thread previewWriterThread([&]() {
        while (isRunning) {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            if (previewReady.exchange(false)) {
                std::vector<uint8_t> toWrite;
                {
                    std::lock_guard<std::mutex> lock(previewMutex);
                    toWrite = previewBuffer;
                }
                if (!toWrite.empty()) {
                    std::ofstream outFile("live_stream.tmp", std::ios::binary | std::ios::trunc);
                    if (outFile.is_open()) {
                        outFile.write((const char*)toWrite.data(), toWrite.size());
                        outFile.close();
                        MoveFileExA("live_stream.tmp", "live_stream.jpg", MOVEFILE_REPLACE_EXISTING);
                    }
                }
            }
        }
    });

    int frameCount = 0;
    long long totalFramesSent = 0;
    double totalLatencyMs = 0.0;
    double lastFpsUpdate = GetTimeMs();
    double lastPreviewTime = 0.0;

    const double targetIntervalMs = (targetFps > 0) ? (1000.0 / (double)targetFps) : 0.0;

    while (isRunning) {
        double frameStart = GetTimeMs();

        // 1. Thực thi chụp màn hình và nén song song đa luồng vào RAM
        bool success = CaptureAndCompressFrame(capturer, jpegBuffer, quality);

        double frameEnd = GetTimeMs();
        double frameLatency = frameEnd - frameStart;
        double now = frameEnd;

        if (success) {
            frameCount++;
            totalFramesSent++;
            totalLatencyMs += frameLatency;

            // 2. Chuyển frame mới nhất cho luồng nền ghi đĩa xem trực tiếp
            if (now - lastPreviewTime >= 200.0) {
                lastPreviewTime = now;
                {
                    std::lock_guard<std::mutex> lock(previewMutex);
                    previewBuffer = jpegBuffer;
                }
                previewReady = true;
            }
        }

        // Báo cáo số liệu hiệu năng mỗi 1 giây
        if (now - lastFpsUpdate >= 1000.0) {
            double elapsed = now - lastFpsUpdate;
            double fps = (frameCount * 1000.0) / elapsed;
            double avgLatency = frameCount > 0 ? (totalLatencyMs / frameCount) : 0.0;
            size_t payloadKb = jpegBuffer.size() / 1024;

            std::cout << "[STREAM ENGINE] FPS: " << (int)(fps + 0.5) 
                      << " | Do tre (Chup + Nen): " << avgLatency << " ms"
                      << " | Kich thuoc anh: " << payloadKb << " KB"
                      << " | Tong frame: " << totalFramesSent << "\n" << std::flush;

            frameCount = 0;
            totalLatencyMs = 0.0;
            lastFpsUpdate = now;
        }

        if (maxFrames > 0 && totalFramesSent >= maxFrames) {
            break;
        }

        // 3. Bộ điều tiết xung nhịp chính xác cao (Precision FPS Pacer)
        if (targetIntervalMs > 0.0) {
            double frameElapsed = GetTimeMs() - frameStart;
            double sleepNeeded = targetIntervalMs - frameElapsed;

            // Nghỉ nhẹ nhàng nếu còn dư thời gian > 2.0ms
            if (sleepNeeded > 2.0) {
                std::this_thread::sleep_for(std::chrono::milliseconds((int)(sleepNeeded - 1.5)));
            }

            // Spin-yield cho phần lẻ mili-giây còn lại để đạt độ chuẩn xác micro-giây
            while (GetTimeMs() - frameStart < targetIntervalMs) {
                std::this_thread::yield();
            }
        }
    }

    // Chờ luồng ghi ảnh phụ kết thúc an toàn
    if (previewWriterThread.joinable()) {
        previewWriterThread.join();
    }

    // Ghi lại frame cuối cùng trước khi thoát
    if (!jpegBuffer.empty()) {
        std::ofstream outFile("live_stream.jpg", std::ios::binary | std::ios::trunc);
        if (outFile.is_open()) {
            outFile.write((const char*)jpegBuffer.data(), jpegBuffer.size());
            outFile.close();
        }
    }

    timeEndPeriod(1);

    std::cout << "\n=======================================================\n";
    std::cout << "=> Da nhan lenh [Ctrl + C]!\n";
    std::cout << "=> Tong so frame da xu ly : " << totalFramesSent << "\n";
    std::cout << "=> File live_stream.jpg da duoc cap nhat.\n";
    std::cout << "=======================================================\n" << std::flush;

    return 0;
}

