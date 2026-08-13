package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual integration smoke test. It deliberately lives outside the default
 * unit-test runner because it performs a billable network request.
 */
public final class QwenAsrSmokeTest {

    private QwenAsrSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: QwenAsrSmokeTest <wav-file>");
        }

        byte[] wav = Files.readAllBytes(Path.of(args[0]));
        QwenSpeechService service = new QwenSpeechService(ModelConfig.getInstance());
        service.initialize();
        String result = service.transcribeWav(wav);
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("Qwen3-ASR returned empty text");
        }
        System.out.println(result.trim());
    }
}
