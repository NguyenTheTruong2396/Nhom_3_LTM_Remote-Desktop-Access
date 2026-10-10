#include "compress.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "image.h"

// Callback ghi byte nén trực tiếp vào RAM vector
static void JpegMemoryCallback(void* context, void* data, int size) {
    auto* buffer = (std::vector<uint8_t>*)context;
    auto* bytes = (const uint8_t*)data;
    buffer->insert(buffer->end(), bytes, bytes + size);
}

bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& rgbaBuffer,
                        std::vector<uint8_t>& outputJpeg,
                        int quality) {
    if (!rawPixels || width <= 0 || height <= 0) {
        return false;
    }

    size_t pixelCount = (size_t)width * height;
    if (rgbaBuffer.size() < pixelCount * 4) {
        rgbaBuffer.resize(pixelCount * 4);
    }

    // 1. Chuyển đổi định dạng BGRA (Win32) -> RGBA (STB chuẩn)
    for (size_t i = 0; i < pixelCount; ++i) {
        rgbaBuffer[i * 4 + 0] = rawPixels[i * 4 + 2]; // R
        rgbaBuffer[i * 4 + 1] = rawPixels[i * 4 + 1]; // G
        rgbaBuffer[i * 4 + 2] = rawPixels[i * 4 + 0]; // B
        rgbaBuffer[i * 4 + 3] = 255;                  // A
    }

    // 2. Xóa dữ liệu frame trước nhưng giữ nguyên bộ đệm đã cấp phát
    outputJpeg.clear();

    // 3. Nén sang JPEG trực tiếp trong RAM
    stbi_write_jpg_to_func(JpegMemoryCallback, &outputJpeg, width, height, 4, rgbaBuffer.data(), quality);

    return !outputJpeg.empty();
}
