#include "compress.h"
#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "image.h"

// Callback ghi byte nén trực tiếp vào RAM vector (đã được đệm 8KB từ STB)
static void JpegMemoryCallback(void* context, void* data, int size) {
    auto* buffer = (std::vector<uint8_t>*)context;
    auto* bytes = (const uint8_t*)data;
    buffer->insert(buffer->end(), bytes, bytes + size);
}

bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& outputJpeg,
                        int quality) {
    if (!rawPixels || width <= 0 || height <= 0) {
        return false;
    }

    // Đảm bảo outputJpeg đã được dự trữ sẵn dung lượng (512KB) tránh realloc
    if (outputJpeg.capacity() < 512 * 1024) {
        outputJpeg.reserve(512 * 1024);
    }
    outputJpeg.clear();

    // Nén trực tiếp từ mảng BGRA sang JPEG (Zero-Copy, không cần chuyển màu qua RGBA)
    stbi_write_jpg_to_func_bgra(JpegMemoryCallback, &outputJpeg, width, height, rawPixels, quality);

    return !outputJpeg.empty();
}

bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& /*rgbaBuffer*/,
                        std::vector<uint8_t>& outputJpeg,
                        int quality) {
    return CompressBgraToJpeg(rawPixels, width, height, outputJpeg, quality);
}
