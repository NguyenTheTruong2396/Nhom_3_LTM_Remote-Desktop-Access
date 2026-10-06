#include <iostream>
#include <vector>
#include <chrono>
#include "capture.h"


// Định nghĩa để bật tính năng ghi/nén ảnh của thư viện stb
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "image.h"

// Callback gom dữ liệu JPEG nén được vào một vector trong RAM
void JpegMemoryCallback(void* context, void* data, int size) {
    auto* buffer = reinterpret_cast<std::vector<uint8_t>*>(context);
    auto* bytes = reinterpret_cast<uint8_t*>(data);
    buffer->insert(buffer->end(), bytes, bytes + size);
}

int main() {
    ScreenCapturer capturer;
    int w = capturer.GetWidth();
    int h = capturer.GetHeight();

    if (w == 0 || h == 0) {
        std::cerr << "Loi: Khong the khoi tao bo chup man hinh!\n";
        return 1;
    }

    std::cout << "=== THU NGHIEM CHUP VA NEN ANH JPEG ===\n";
    std::cout << "Do phan giai: " << w << " x " << h << "\n";

    // 1. Chụp thử 1 khung hình
    auto t1 = std::chrono::high_resolution_clock::now();
    capturer.Capture();
    auto t2 = std::chrono::high_resolution_clock::now();

    // 2. Chuyển đổi định dạng BGRA (GDI) sang RGBA để nén đúng màu
    const uint8_t* rawPixels = reinterpret_cast<const uint8_t*>(capturer.GetBuffer());
    size_t pixelCount = w * h;
    std::vector<uint8_t> rgbaPixels(pixelCount * 4);

    for (size_t i = 0; i < pixelCount; ++i) {
        rgbaPixels[i * 4 + 0] = rawPixels[i * 4 + 2]; // R
        rgbaPixels[i * 4 + 1] = rawPixels[i * 4 + 1]; // G
        rgbaPixels[i * 4 + 2] = rawPixels[i * 4 + 0]; // B
        rgbaPixels[i * 4 + 3] = 255;                  // A
    }

    // 3. Nén ảnh thành JPEG trong bộ nhớ RAM (chất lượng 60%)
    std::vector<uint8_t> compressedJpeg;
    auto t3 = std::chrono::high_resolution_clock::now();

    // w, h, 4 kênh màu (RGBA), mảng pixel, chất lượng nén (1-100)
    stbi_write_jpg_to_func(JpegMemoryCallback, &compressedJpeg, w, h, 4, rgbaPixels.data(), 60);

    auto t4 = std::chrono::high_resolution_clock::now();

    // Tính toán thời gian
    auto captureTime = std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count();
    auto compressTime = std::chrono::duration_cast<std::chrono::milliseconds>(t4 - t3).count();

    // 4. Lưu ra file để mở lên kiểm tra xem chất lượng hình ảnh
    stbi_write_jpg("test_screen.jpg", w, h, 4, rgbaPixels.data(), 60);

    std::cout << "\n--- KET QUA ---\n";
    std::cout << "Thoi gian chup: " << captureTime << " ms\n";
    std::cout << "Thoi gian nen JPEG: " << compressTime << " ms\n";
    std::cout << "Dung luong goc (Raw): " << (pixelCount * 4) / 1024 << " KB\n";
    std::cout << "Dung luong sau nen (JPEG): " << compressedJpeg.size() / 1024 << " KB\n";
    std::cout << "Da luu anh kiem tra: test_screen.jpg\n";

    return 0;
}