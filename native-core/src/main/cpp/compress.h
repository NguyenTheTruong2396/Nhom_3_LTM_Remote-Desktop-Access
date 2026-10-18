#pragma once
#include <vector>
#include <cstdint>

/**
 * Nén dữ liệu pixel BGRA (từ Win32 GDI) sang JPEG in-memory trong RAM.
 * Sử dụng thư viện STB Image Write.
 *
 * @param rawPixels   Con trỏ mảng byte BGRA 32-bit từ màn hình
 * @param width       Chiều rộng khung hình
 * @param height      Chiều cao khung hình
 * @param rgbaBuffer  Bộ đệm chuyển đổi màu trung gian RGBA (tái sử dụng)
 * @param outputJpeg  Bộ đệm nhận kết quả byte JPEG
 * @param quality     Mức chất lượng nén JPEG (mặc định 60 cho remote desktop)
 * @return true nếu nén thành công, false nếu thất bại
 */
bool CompressBgraToJpeg(const uint8_t* rawPixels, int width, int height,
                        std::vector<uint8_t>& rgbaBuffer,
                        std::vector<uint8_t>& outputJpeg,
                        int quality = 60);
