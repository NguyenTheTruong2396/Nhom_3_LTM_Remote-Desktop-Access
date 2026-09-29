package com.remote.server.nativeapi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JNI Bridge responsible for loading the native C/C++ library
 * and declaring native functions for screen capture and compression.
 */
public class JniBridge {

    private static final String LIB_RESOURCE_NAME = "/native-core.dll";
    private static final String TEMP_LIB_PREFIX = "native-core-";
    private static final String TEMP_LIB_SUFFIX = ".dll";
    
    private static boolean libraryLoaded = false;

    /**
     * Declares test native method implemented in C/C++.
     */
    public static native void testJni();

    /**
     * Loads the native library by extracting it from resources to a temp file,
     * then invoking System.load.
     *
     * @return true if loaded successfully, false otherwise.
     */
    public static synchronized boolean loadNativeLibrary() {
        if (libraryLoaded) {
            return true;
        }

        System.out.println("[JniBridge] Attempting to load native library from resource: " + LIB_RESOURCE_NAME);

        try (InputStream in = JniBridge.class.getResourceAsStream(LIB_RESOURCE_NAME)) {
            if (in == null) {
                System.err.println("[JniBridge] Native library resource not found: " + LIB_RESOURCE_NAME
                        + " (Native DLL might not be compiled yet in Week 1).");
                return false;
            }

            File tempFile = File.createTempFile(TEMP_LIB_PREFIX, TEMP_LIB_SUFFIX);
            tempFile.deleteOnExit();

            try (OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            System.out.println("[JniBridge] Extracted native library to: " + tempFile.getAbsolutePath());
            System.load(tempFile.getAbsolutePath());
            libraryLoaded = true;
            System.out.println("[JniBridge] Native library loaded successfully.");
            return true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[JniBridge] UnsatisfiedLinkError while loading native library: " + e.getMessage());
            return false;
        } catch (IOException e) {
            System.err.println("[JniBridge] IOException while extracting native library: " + e.getMessage());
            return false;
        } catch (Exception e) {
            System.err.println("[JniBridge] Unexpected error while loading native library: " + e.getMessage());
            return false;
        }
    }

    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }
}
