#include "jni_bridge.h"
#include "capture.h"
#include "compress.h"
#include <mutex>
#include <iostream>

// Singleton quản lý capturer và bộ đệm JPEG tái sử dụng (Buffer Reuse)
static ScreenCapturer* g_capturer = nullptr;
static std::vector<uint8_t> g_jpegBuffer;
static std::mutex g_captureMutex;

JNIEXPORT void JNICALL Java_com_remote_server_nativeapi_JniBridge_testJni(JNIEnv *env, jclass clazz) {
    std::cout << "[native-core C++] JNI testJni: Thu vien C/C++ da duoc nap thanh cong!\n";
}

JNIEXPORT jbyteArray JNICALL Java_com_remote_server_nativeapi_JniBridge_captureFrame(JNIEnv *env, jclass clazz, jint quality) {
    std::lock_guard<std::mutex> lock(g_captureMutex);

    // Khởi tạo ScreenCapturer một lần duy nhất
    if (!g_capturer) {
        g_capturer = new ScreenCapturer();
        g_jpegBuffer.reserve(512 * 1024); // Đặt trước 512KB cho mỗi frame
    }

    // 1. Chụp màn hình bằng Windows GDI siêu tốc (SRCCOPY + Con trỏ chuột không lag)
    if (!g_capturer->Capture(true)) {
        return nullptr;
    }

    int w = g_capturer->GetWidth();
    int h = g_capturer->GetHeight();
    const uint8_t* rawPixels = (const uint8_t*)g_capturer->GetBuffer();
    if (!rawPixels || w <= 0 || h <= 0) {
        return nullptr;
    }

    // 2. Nén trực tiếp từ BGRA -> JPEG Zero-Copy trong RAM (Không cần qua mảng phụ RGBA)
    if (!CompressBgraToJpeg(rawPixels, w, h, g_jpegBuffer, quality)) {
        return nullptr;
    }

    // 3. Đóng gói chuyển đổi mảng byte C++ sang jbyteArray trả về cho Java
    jsize jpegSize = (jsize)g_jpegBuffer.size();
    jbyteArray result = env->NewByteArray(jpegSize);
    if (!result) {
        return nullptr;
    }

    env->SetByteArrayRegion(result, 0, jpegSize, (const jbyte*)g_jpegBuffer.data());
    return result;
}
