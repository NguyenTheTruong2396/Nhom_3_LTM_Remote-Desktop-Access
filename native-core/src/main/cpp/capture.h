#pragma once
#include <windows.h>
#include <iostream>

class ScreenCapturer {
private:
    HDC hScreenDC = nullptr;
    HDC hMemoryDC = nullptr;
    HBITMAP hBitmap = nullptr;
    HBITMAP hOldBitmap = nullptr;
    void* pBits = nullptr;
    int width = 0;
    int height = 0;

public:
    ScreenCapturer() {
        // Lấy độ phân giải toàn màn hình
        width = GetSystemMetrics(SM_CXSCREEN);
        height = GetSystemMetrics(SM_CYSCREEN);

        hScreenDC = GetDC(NULL);
        if (!hScreenDC) return;

        hMemoryDC = CreateCompatibleDC(hScreenDC);
        if (!hMemoryDC) return;

        // Cấu hình Bitmap 32-bit màu
        BITMAPINFO bmi = { 0 };
        bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height; // Top-down: pixel quét từ trên xuống dưới
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = BI_RGB;

        hBitmap = CreateDIBSection(hMemoryDC, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
        if (!hBitmap || !pBits) {
            std::cerr << "[ScreenCapturer] Loi CreateDIBSection: " << GetLastError() << "\n";
        }
        if (hBitmap) {
            hOldBitmap = (HBITMAP)SelectObject(hMemoryDC, hBitmap);
            if (!hOldBitmap) {
                std::cerr << "[ScreenCapturer] Loi SelectObject: " << GetLastError() << "\n";
            }
        }
    }

    ~ScreenCapturer() {
        // Giải phóng tài nguyên GDI tránh tràn RAM
        if (hMemoryDC && hOldBitmap) SelectObject(hMemoryDC, hOldBitmap);
        if (hBitmap) DeleteObject(hBitmap);
        if (hMemoryDC) DeleteDC(hMemoryDC);
        if (hScreenDC) ReleaseDC(NULL, hScreenDC);
    }

    // Chụp lại màn hình vào bộ nhớ
    bool Capture() {
        if (!hMemoryDC) return false;
        HDC hScreen = GetDC(NULL);
        if (!hScreen) return false;

        BOOL res = BitBlt(hMemoryDC, 0, 0, width, height, hScreen, 0, 0, SRCCOPY | CAPTUREBLT);
        ReleaseDC(NULL, hScreen);
        return res != FALSE;
    }

    int GetWidth() const { return width; }
    int GetHeight() const { return height; }
    const void* GetBuffer() const { return pBits; }
};