package com.example.ahakey.service;

import java.util.Arrays;

/** Standalone test for the bounded in-memory PCM pre-roll buffer. */
public final class QwenHotCaptureBufferSmokeTest {

    private QwenHotCaptureBufferSmokeTest() {
    }

    public static void main(String[] args) {
        QwenSpeechService.RollingPcmBuffer buffer = new QwenSpeechService.RollingPcmBuffer(5);
        buffer.append(new byte[] {1, 2, 3}, 0, 3);
        require(Arrays.equals(new byte[] {1, 2, 3}, buffer.snapshot()), "Initial append failed");

        buffer.append(new byte[] {4, 5, 6, 7}, 0, 4);
        require(Arrays.equals(new byte[] {3, 4, 5, 6, 7}, buffer.snapshot()), "Rolling order failed");

        buffer.append(new byte[] {8, 9, 10, 11, 12, 13, 14}, 0, 7);
        require(Arrays.equals(new byte[] {10, 11, 12, 13, 14}, buffer.snapshot()), "Large append failed");

        buffer.clear();
        require(buffer.snapshot().length == 0, "Clear did not erase the rolling buffer");
        System.out.println("Qwen hot capture buffer smoke test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
