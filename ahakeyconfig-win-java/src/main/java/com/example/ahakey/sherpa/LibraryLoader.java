package com.example.ahakey.sherpa;

/**
 * Minimal native loader retained for the optional local provider.
 * The Qwen cloud build does not invoke this class.
 */
public final class LibraryLoader {

    private static boolean loaded;

    private LibraryLoader() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }
}
