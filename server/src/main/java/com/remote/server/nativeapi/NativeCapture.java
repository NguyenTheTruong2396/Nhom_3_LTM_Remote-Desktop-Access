package com.remote.server.nativeapi;

/**
 * JNI stub for Week 2: declares the native screen capture method.
 *
 * The actual C/C++ implementation (jni_bridge.cpp) is provided by Dev 2 and
 * compiled into native-core.dll.  Until that DLL is available, this class
 * compiles cleanly and throws {@link UnsatisfiedLinkError} at runtime if the
 * native method is called without the library being loaded.
 *
 * Usage:
 * <pre>
 *     if (JniBridge.loadNativeLibrary()) {
 *         byte[] jpegBytes = NativeCapture.captureAndCompress(60);
 *         PacketBuilder.writeFrameData(dos, jpegBytes);
 *     }
 * </pre>
 */
public final class NativeCapture {

    private NativeCapture() {}

    /**
     * Captures the primary screen and returns the result as a JPEG byte array.
     *
     * Implemented in C++ ({@code jni_bridge.cpp}).
     *
     * @param jpegQuality JPEG compression quality, 1–100 (60 is the agreed default).
     * @return byte array containing the compressed JPEG image data.
     * @throws UnsatisfiedLinkError if the native library has not been loaded.
     */
    public static native byte[] captureAndCompress(int jpegQuality);
}
