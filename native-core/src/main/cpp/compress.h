#pragma once
#include <vector>
#include <cstdint>

/**
 * Nén dữ liệu pixel BGRA (từ Win32 GDI) trực tiếp sang JPEG in-memory trong RAM.
 * Tối ưu Zero-Copy: Nén thẳng từ bộ đệm BGRA, không cần bộ đệm trung gian RGBA.
 *
 * @param rawPixels   Con trỏ mảng byte BGRA 32-bit từ màn hình
 * @param width       Chiều rộng khung hình
 * @param height      Chiều cao khung hình
 * @param outputJpeg  Bộ đệm nhận kết quả byte JPEG
 * @param quality     Mức chất lượng nén JPEG (mặc định 60 cho remote desktop)
 * @return true nếu nén thành công, false nếu thất bại
 */
bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& outputJpeg,
                        int quality = 60);

// Quá tải tương thích ngược (giữ lại để mã cũ không bị lỗi biên dịch)
bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& rgbaBuffer,
                        std::vector<uint8_t>& outputJpeg,
                        int quality = 60);
