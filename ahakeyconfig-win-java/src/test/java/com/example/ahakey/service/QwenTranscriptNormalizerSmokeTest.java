package com.example.ahakey.service;

/** Standalone, no-network smoke test for conservative transcript cleanup. */
public final class QwenTranscriptNormalizerSmokeTest {

    private QwenTranscriptNormalizerSmokeTest() {
    }

    public static void main(String[] args) {
        String transcriptionContext = QwenSpeechService.buildTranscriptionContext("林知夏、Codex");
        require(transcriptionContext.contains("自然短停顿用“，”"), "ASR prompt is missing comma guidance");
        require(transcriptionContext.contains("明确疑问用“？”"), "ASR prompt is missing question prosody guidance");
        require(transcriptionContext.contains("中文省略号“……”"), "ASR prompt is missing ellipsis guidance");
        require(transcriptionContext.contains("林知夏"), "ASR prompt is missing personal terminology");
        require(transcriptionContext.contains("不要为了显得活泼"), "ASR prompt is missing conservative fallback");
        require(
            "然后我想，这个任务".equals(QwenSpeechService.normalizeTranscript("然后我想，，这个任务")),
            "Chinese double comma was not collapsed"
        );
        require(
            "另外就是，比如说".equals(QwenSpeechService.normalizeTranscript("，另外就是，比如说")),
            "Leading comma was not removed"
        );
        require(
            "hello, world".equals(QwenSpeechService.normalizeTranscript("hello,, world")),
            "ASCII double comma was not collapsed"
        );
        require(
            "保留，正常标点。".equals(QwenSpeechService.normalizeTranscript("保留，正常标点。")),
            "Normal punctuation changed unexpectedly"
        );
        require(
            "实挺有用的。另外就是".equals(QwenSpeechService.normalizeTranscript("实挺有用的。，另外就是")),
            "Conflicting sentence/comma punctuation was not collapsed"
        );
        require(
            "OK，我觉得挺好的。灯光还不错。".equals(
                QwenSpeechService.normalizeTranscript("OK，我觉得挺好的。，，灯光还不错。")),
            "Observed Qwen punctuation artifact was not normalized"
        );
        require(
            "一句。下一句".equals(QwenSpeechService.normalizeTranscript("一句。。下一句")),
            "Chinese double period was not collapsed"
        );
        require(
            "hello. next".equals(QwenSpeechService.normalizeTranscript("hello.. next")),
            "ASCII double period was not collapsed"
        );
        require(
            "一句。下一句".equals(QwenSpeechService.normalizeTranscript("一句。.下一句")),
            "Mixed period run was not collapsed"
        );
        require(
            "下一句".equals(QwenSpeechService.normalizeTranscript("。下一句")),
            "Leading Chinese period was not removed"
        );
        require(
            "next".equals(QwenSpeechService.normalizeTranscript(". next")),
            "Leading ASCII period fragment was not removed"
        );
        require(
            "等等……继续...真的？！".equals(QwenSpeechService.normalizeTranscript("等等……继续...真的？！")),
            "Meaningful ellipsis or mixed punctuation changed unexpectedly"
        );
        String once = QwenSpeechService.normalizeTranscript("。一句。。下一句！！");
        require(
            once.equals(QwenSpeechService.normalizeTranscript(once)),
            "Transcript normalization is not idempotent"
        );
        System.out.println("Qwen transcript normalizer smoke test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
