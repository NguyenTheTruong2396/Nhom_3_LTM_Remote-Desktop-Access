#include "windows.h"
#include "iostream"
#include "vector"
#include "thread"
#include "chrono"
#include "cstdint"
#include "cstring"
#include "fstream"
#include "atomic"
#include "capture.h"

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
std::atomic isRunning(true);
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
 * - rgbaBuffer   : Bộ đệm chuyển màu trung gian (tái sử dụng, không new/delete)
 * - outputJpeg   : Mảng byte JPEG kết quả để giao cho JNI / Socket
 * - quality      : Chất lượng JPEG (khuyến nghị 55 - 65 cho Remote Desktop)
 */
bool CaptureAndCompressFrame(ScreenCapturer& capturer, 
                             std::vector<uint8_t>& rgbaBuffer, 
                             std::vector<uint8_t>& outputJpeg, 
                             int quality = 60) 
{
    int w = capturer.GetWidth();
    int h = capturer.GetHeight();
    if (w == 0 || h == 0) return false;

    // 1. Chụp màn hình GDI
    capturer.Capture();
    const uint8_t* rawPixels = (const uint8_t*)capturer.GetBuffer();
    if (!rawPixels) return false;

    // 2. Chuyển đổi định dạng BGRA -> RGBA
    size_t pixelCount = (size_t)w * h;
    for (size_t i = 0; i < pixelCount; ++i) {
        rgbaBuffer[i * 4 + 0] = rawPixels[i * 4 + 2]; // R
        rgbaBuffer[i * 4 + 1] = rawPixels[i * 4 + 1]; // G
        rgbaBuffer[i * 4 + 2] = rawPixels[i * 4 + 0]; // B
        rgbaBuffer[i * 4 + 3] = 255;                  // A
    }

    // 3. Xóa dữ liệu frame trước nhưng giữ nguyên dung lượng đã cấp phát
    outputJpeg.clear();

    // 4. Nén sang JPEG trực tiếp trong thanh RAM
    stbi_write_jpg_to_func(JpegMemoryCallback, &outputJpeg, w, h, 4, rgbaBuffer.data(), quality);

    return !outputJpeg.empty();
}

int main() {
    // Đăng ký bắt sự kiện Ctrl + C để dọn dẹp tiến trình
    SetConsoleCtrlHandler(ConsoleHandler, TRUE);

    ScreenCapturer capturer;
    int w = capturer.GetWidth();
    int h = capturer.GetHeight();

    if (w == 0 || h == 0) {
        std::cerr << "Loi: Khong the khoi tao bo chup man hinh!\n";
        return 1;
    }

    std::cout << "=== REAL-TIME CAPTURE & COMPRESS (LIVE PREVIEW) ===\n";
    std::cout << "Do phan giai : " << w << " x " << h << "\n";
    std::cout << "Dang chup va nen lien tuc trong RAM...\n";
    std::cout << "File xem truc tiep: live_stream.jpg (ghi de lien tuc)\n";
    std::cout << "Nhan [Ctrl + C] de dung va tu dong xoa sach anh khoi o dia.\n\n";

    // Khởi tạo bộ đệm cố định một lần duy nhất (Buffer Reuse)
    size_t pixelCount = (size_t)w * h;
    std::vector<uint8_t> rgbaBuffer(pixelCount * 4);
    std::vector<uint8_t> jpegBuffer;
    jpegBuffer.reserve(256 * 1024); // Đặt trước 256KB cho mỗi frame

    int frameCount = 0;
    long long totalFramesSent = 0;
    double totalLatencyMs = 0.0;
    double lastFpsUpdate = GetTimeMs();

    while (isRunning) {
        double frameStart = GetTimeMs();

        // 1. Thực thi chụp màn hình và nén vào RAM
        bool success = CaptureAndCompressFrame(capturer, rgbaBuffer, jpegBuffer, 60);

        double frameEnd = GetTimeMs();
        double frameLatency = frameEnd - frameStart;

        if (success) {
            frameCount++;
            totalFramesSent++;
            totalLatencyMs += frameLatency;

            // 2. Ghi đè vào 1 file duy nhất để bạn mở lên xem trực tiếp bằng mắt
            std::ofstream outFile("live_stream.jpg", std::ios::binary | std::ios::trunc);
            if (outFile.is_open()) {
                outFile.write((const char*)jpegBuffer.data(), jpegBuffer.size());
                outFile.close();
            }
        }

        // Báo cáo số liệu hiệu năng mỗi 1 giây
        double now = GetTimeMs();
        if (now - lastFpsUpdate >= 1000.0) {
            double elapsed = now - lastFpsUpdate;
            double fps = (frameCount * 1000.0) / elapsed;
            double avgLatency = frameCount > 0 ? (totalLatencyMs / frameCount) : 0.0;
            size_t payloadKb = jpegBuffer.size() / 1024;

            std::cout << "[STREAM ENGINE] FPS: " << (int)fps 
                      << " | Do tre (Chup + Nen): " << avgLatency << " ms"
                      << " | Kich thuoc anh: " << payloadKb << " KB"
                      << " | Tong frame: " << totalFramesSent << "\n";

            frameCount = 0;
            totalLatencyMs = 0.0;
            lastFpsUpdate = now;
        }

        // Tạm nghỉ 1ms để tránh chiếm dụng 100% CPU
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    // Tự động xóa file ảnh ngay khi người dùng bấm Ctrl + C
    remove("live_stream.jpg");

    std::cout << "\n=======================================================\n";
    std::cout << "=> Da nhan lenh [Ctrl + C]!\n";
    std::cout << "=> Tong so frame da chup & nen : " << totalFramesSent << "\n";
    std::cout << "=> Da xoa sach file live_stream.jpg khoi o dia.\n";
    std::cout << "=> Bo nho RAM da duoc giai phong hoan toan.\n";
    std::cout << "=======================================================\n";

    return 0;
}

