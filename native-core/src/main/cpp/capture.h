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

    void cleanup() {
        if (hMemoryDC && hOldBitmap) {
            SelectObject(hMemoryDC, hOldBitmap);
            hOldBitmap = nullptr;
        }
        if (hBitmap) {
            DeleteObject(hBitmap);
            hBitmap = nullptr;
        }
        if (hMemoryDC) {
            DeleteDC(hMemoryDC);
            hMemoryDC = nullptr;
        }
        if (hScreenDC) {
            ReleaseDC(NULL, hScreenDC);
            hScreenDC = nullptr;
        }
        pBits = nullptr;
    }

    bool init() {
        width = GetSystemMetrics(SM_CXSCREEN);
        height = GetSystemMetrics(SM_CYSCREEN);
        if (width <= 0 || height <= 0) return false;

        hScreenDC = GetDC(NULL);
        if (!hScreenDC) return false;

        hMemoryDC = CreateCompatibleDC(hScreenDC);
        if (!hMemoryDC) {
            ReleaseDC(NULL, hScreenDC);
            hScreenDC = nullptr;
            return false;
        }

        BITMAPINFO bmi = { 0 };
        bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height; // Top-down
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = BI_RGB;

        hBitmap = CreateDIBSection(hMemoryDC, &bmi, DIB_RGB_COLORS, &pBits, NULL, 0);
        if (!hBitmap || !pBits) {
            cleanup();
            return false;
        }

        hOldBitmap = (HBITMAP)SelectObject(hMemoryDC, hBitmap);
        return true;
    }

public:
    ScreenCapturer() {
        init();
    }

    ~ScreenCapturer() {
        cleanup();
    }

    // Chụp lại màn hình vào bộ nhớ (tối ưu không re-create DC, không lag CAPTUREBLT)
    bool Capture(bool drawCursor = true) {
        // Kiểm tra nếu màn hình thay đổi độ phân giải đột ngột
        int curW = GetSystemMetrics(SM_CXSCREEN);
        int curH = GetSystemMetrics(SM_CYSCREEN);
        if (curW != width || curH != height || !hMemoryDC || !pBits) {
            cleanup();
            if (!init()) return false;
        }

        // BitBlt với SRCCOPY trực tiếp từ hScreenDC (2-4ms, không làm GPU DWM stall)
        BOOL res = BitBlt(hMemoryDC, 0, 0, width, height, hScreenDC, 0, 0, SRCCOPY);
        if (!res) {
            // Thử làm mới hScreenDC nếu bị mất handle DC
            if (hScreenDC) ReleaseDC(NULL, hScreenDC);
            hScreenDC = GetDC(NULL);
            if (hScreenDC) {
                res = BitBlt(hMemoryDC, 0, 0, width, height, hScreenDC, 0, 0, SRCCOPY);
            }
        }

        // Vẽ con trỏ chuột mượt mà lên memory DC (cache hotspot tránh cấp phát lại ICONINFO liên tục)
        if (res && drawCursor) {
            CURSORINFO cursorInfo = { 0 };
            cursorInfo.cbSize = sizeof(cursorInfo);
            if (GetCursorInfo(&cursorInfo) && (cursorInfo.flags & CURSOR_SHOWING)) {
                static HCURSOR lastCursor = NULL;
                static int hotspotX = 0;
                static int hotspotY = 0;
                if (cursorInfo.hCursor != lastCursor) {
                    lastCursor = cursorInfo.hCursor;
                    ICONINFO iconInfo = { 0 };
                    if (GetIconInfo(cursorInfo.hCursor, &iconInfo)) {
                        hotspotX = (int)iconInfo.xHotspot;
                        hotspotY = (int)iconInfo.yHotspot;
                        if (iconInfo.hbmColor) DeleteObject(iconInfo.hbmColor);
                        if (iconInfo.hbmMask) DeleteObject(iconInfo.hbmMask);
                    }
                }
                int x = cursorInfo.ptScreenPos.x - hotspotX;
                int y = cursorInfo.ptScreenPos.y - hotspotY;
                DrawIconEx(hMemoryDC, x, y, cursorInfo.hCursor, 0, 0, 0, NULL, DI_NORMAL);
            }
        }

        return res != FALSE;
    }

    int GetWidth() const { return width; }
    int GetHeight() const { return height; }
    const void* GetBuffer() const { return pBits; }
};