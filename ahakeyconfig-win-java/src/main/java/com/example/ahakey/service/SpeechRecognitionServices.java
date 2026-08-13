package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

/**
 * Creates the configured ASR provider while leaving the rest of the AhaKey
 * recording, HUD and keyboard-injection flow unchanged.
 */
public final class SpeechRecognitionServices {

    private SpeechRecognitionServices() {
    }

    public static SpeechRecognitionService create(ModelConfig config) {
        String provider = config.getAsrProvider().trim().toLowerCase();
        return switch (provider) {
            case "qwen", "qwen3", "dashscope" -> new QwenSpeechService(config);
            case "local", "sherpa", "paraformer" -> new SpeechService();
            default -> throw new IllegalArgumentException("不支持的语音识别 provider: " + provider);
        };
    }
}
