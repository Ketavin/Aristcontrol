package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone, no-network smoke test for the fail-open Qwen text polish pass. */
public final class QwenTextPolisherSmokeTest {

    private QwenTextPolisherSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        acceptsConservativeRewriteAndBuildsLowCostRequest();
        rejectsChangedTechnicalTermsAndNumbers();
        failsOpenOnEmptyOrHttpError();
        validatesProtectedContentWithoutNetwork();
        selectsChatAndWorkPrompts();
        rejectsChatRewritesThatFlattenTone();
        acceptsCollapsedRepeatedChatFillers();
        appliesDeterministicCleanupBeforeFallback();
        preservesEllipsesAcrossPolishCleanup();
        acceptsOnlyCueBackedChatPunctuation();
        acceptsOnlyConservativeChatEmoji();
        keepsNewEmojiAtWholeMessageEndOnly();
        keepsMixedTerminalClusterAtomic();
        preservesSegmentBoundariesAndAsrProsody();
        preservesWorkCommaSegmentBoundaries();
        preservesInternalExpressiveBoundaries();
        preservesProtectedPersonalNames();
        keepsSafeSegmentsWhenAnotherSegmentIsRejected();
        selectsModeFromTargetApplication();
        System.out.println("Qwen text polisher smoke test passed");
    }

    private static void acceptsConservativeRewriteAndBuildsLowCostRequest() throws Exception {
        String original = "嗯嗯，我用 Codex 507C 试一下。。";
        String rewritten = "我用 Codex 507C 测试一下。";
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        QwenTextPolisher polisher = polisherReturning(200, rewritten, captured);

        require(rewritten.equals(polisher.polishOrOriginal(original)), "Conservative rewrite was rejected");
        HttpRequest request = captured.get();
        require(request != null, "Request was not sent");
        require(
            Duration.ofSeconds(8).equals(request.timeout().orElseThrow()),
            "Text polish request did not use the configured 8-second timeout"
        );
        String body = request.bodyPublisher().orElseThrow()
            .contentLength() == 0 ? "" : readBody(request);
        require(body.contains("\"model\":\"qwen-flash\""), "qwen-flash model was not selected");
        require(body.contains("\"enable_thinking\":false"), "Thinking was not disabled");
        require(body.contains("\"temperature\":0.1"), "Low temperature was not applied");
        require(body.contains("Codex") && body.contains("507C"), "Transcript was not included in request");
    }

    private static void rejectsChangedTechnicalTermsAndNumbers() {
        String original = "请用 Codex CLI 打开 AhaKey 507C，端口是 8765。";
        QwenTextPolisher changedTerm = polisherReturning(200, "请用 Claude 打开 AhaKey 507C，端口是 8765。", null);
        QwenTextPolisher changedNumber = polisherReturning(200, "请用 Codex CLI 打开 AhaKey 507C，端口是 9000。", null);

        require(original.equals(changedTerm.polishOrOriginal(original)), "Changed English term was accepted");
        require(original.equals(changedNumber.polishOrOriginal(original)), "Changed number was accepted");
    }

    private static void failsOpenOnEmptyOrHttpError() {
        String original = "嗯，这是一段需要轻度整理的测试文本。";
        String cleaned = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.WORK);
        QwenTextPolisher empty = polisherReturning(200, "", null);
        QwenTextPolisher failed = polisherReturning(429, "rate limited", null);

        require(cleaned.equals(empty.polishOrOriginal(original)), "Empty response did not use rule-cleaned text");
        require(cleaned.equals(failed.polishOrOriginal(original)), "HTTP error did not use rule-cleaned text");
    }

    private static void validatesProtectedContentWithoutNetwork() {
        require(
            QwenTextPolisher.isSafeRewrite(
                "嗯，请运行 npm run dev，然后打开 C:\\work\\AhaKey\\app.java。",
                "请运行 npm run dev，打开 C:\\work\\AhaKey\\app.java。"
            ),
            "Safe command/path cleanup was rejected"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "请打开 https://example.com/api?v=3。",
                "请打开 https://example.com/api?v=4。"
            ),
            "Changed URL was accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("这是原始文本。", "整理后的文本：这是原始文本。"),
            "Model wrapper text was accepted"
        );
    }

    private static void selectsChatAndWorkPrompts() throws Exception {
        String original = "这个这个问题我觉得可能还要再看看吧。";
        String cleaned = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.CHAT);
        AtomicReference<HttpRequest> chatRequest = new AtomicReference<>();
        AtomicReference<HttpRequest> workRequest = new AtomicReference<>();
        QwenTextPolisher chat = polisherReturning(200, cleaned, chatRequest);
        QwenTextPolisher work = polisherReturning(
            200,
            QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.WORK),
            workRequest
        );

        chat.polishOrOriginal(original, QwenTextPolisher.Mode.CHAT);
        work.polishOrOriginal(original, QwenTextPolisher.Mode.WORK);

        require(readBody(chatRequest.get()).contains("微信聊天语音转写"), "Chat prompt was not selected");
        require(!readBody(workRequest.get()).contains("微信聊天语音转写"), "Work prompt used chat instructions");
        require(readBody(workRequest.get()).contains("segments"), "Structured segments were not requested");
    }

    private static void rejectsChatRewritesThatFlattenTone() {
        String original = "我觉得这个事可能还要再看看吧，你觉得呢？";
        String flattened = "这个事需要再看看。";
        require(
            !QwenTextPolisher.isSafeRewrite(original, flattened, QwenTextPolisher.Mode.CHAT),
            "Chat mode accepted a rewrite that flattened uncertainty and tone"
        );
    }

    private static void acceptsCollapsedRepeatedChatFillers() {
        String original = "嗯嗯，这个这个我觉得可以吧。";
        String natural = "嗯，这个我觉得可以吧。";
        require(
            QwenTextPolisher.isSafeRewrite(original, natural, QwenTextPolisher.Mode.CHAT),
            "Chat mode rejected a natural collapse of repeated fillers"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(original, "这个我觉得可以。", QwenTextPolisher.Mode.CHAT),
            "Chat mode accepted removal of all conversational tone"
        );
    }

    private static void appliesDeterministicCleanupBeforeFallback() {
        String original = "嗯嗯，这个这个方案可以。。";
        String chat = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.CHAT);
        String work = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.WORK);
        require("嗯，这个方案可以。".equals(chat), "Chat deterministic cleanup was incorrect: " + chat);
        require("这个方案可以。".equals(work), "Work deterministic cleanup was incorrect: " + work);
    }

    private static void preservesEllipsesAcrossPolishCleanup() {
        String original = "这个我还得再想想……稍后回复...";
        String chat = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.CHAT);
        String work = QwenTextPolisher.deterministicCleanup(original, QwenTextPolisher.Mode.WORK);
        require(original.equals(chat), "Chat cleanup damaged ellipses: " + chat);
        require(original.equals(work), "Work cleanup damaged ellipses: " + work);
        require(
            chat.equals(QwenTextPolisher.deterministicCleanup(chat, QwenTextPolisher.Mode.CHAT)),
            "Chat ellipsis cleanup was not idempotent"
        );
    }

    private static void acceptsOnlyCueBackedChatPunctuation() {
        require(
            QwenTextPolisher.isSafeRewrite("你明天来吗。", "你明天来吗？", QwenTextPolisher.Mode.CHAT),
            "Cue-backed question mark was rejected"
        );
        require(
            QwenTextPolisher.isSafeRewrite("这个我还得再想想。", "这个我还得再想想……", QwenTextPolisher.Mode.CHAT),
            "Cue-backed ellipsis was rejected"
        );
        require(
            QwenTextPolisher.isSafeRewrite("太好了。", "太好了！", QwenTextPolisher.Mode.CHAT),
            "Cue-backed exclamation mark was rejected"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("我下午三点到。", "我下午三点到！", QwenTextPolisher.Mode.CHAT),
            "Neutral statement gained an exclamation mark"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("明天见。", "【明天见！】", QwenTextPolisher.Mode.CHAT),
            "Chat rewrite added structural punctuation"
        );
    }

    private static void acceptsOnlyConservativeChatEmoji() {
        require(
            QwenTextPolisher.isSafeRewrite(
                "哈哈这也太巧了。", "哈哈这也太巧了。😂", QwenTextPolisher.Mode.CHAT
            ),
            "Cue-backed chat emoji was rejected"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "哈哈这也太巧了。", "哈哈😂这也太巧了。", QwenTextPolisher.Mode.CHAT
            ),
            "Mid-sentence emoji was accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "哈哈这也太巧了。", "哈哈这也太巧了。😂😂", QwenTextPolisher.Mode.CHAT
            ),
            "Multiple new emoji were accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "我下午三点到。", "我下午三点到。👍", QwenTextPolisher.Mode.CHAT
            ),
            "Emoji without a strong cue was accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "哈哈这也太巧了。", "哈哈这也太巧了。🙂", QwenTextPolisher.Mode.CHAT
            ),
            "A non-allowlisted emoji was accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "太好了，手术结束了。", "太好了，手术结束了。🎉", QwenTextPolisher.Mode.CHAT
            ),
            "A serious-context message gained an emoji"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "哈哈，不过我可能搞错了。", "哈哈，不过我可能搞错了。😂", QwenTextPolisher.Mode.CHAT
            ),
            "An uncertain or conflicting message gained an emoji"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "可能太好了。", "可能太好了。🎉", QwenTextPolisher.Mode.CHAT
            ),
            "An uncertain celebration gained an emoji"
        );
        require(
            QwenTextPolisher.isSafeRewrite(
                "我真的很想你。", "我真的很想你。❤️", QwenTextPolisher.Mode.CHAT
            ),
            "Cue-backed affection emoji was rejected"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "哈哈这也太巧了。", "哈哈这也太巧了。😂", QwenTextPolisher.Mode.WORK
            ),
            "Work mode added an emoji"
        );
        require(
            QwenTextPolisher.isSafeRewrite(
                "太好了🎉", "太好了🎉", QwenTextPolisher.Mode.CHAT
            ),
            "Existing emoji was not preserved"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "太好了🎉", "太好了🎉🎉", QwenTextPolisher.Mode.CHAT
            ),
            "Existing emoji was duplicated"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "太好🎉了。", "太好了。🎉", QwenTextPolisher.Mode.CHAT
            ),
            "An existing emoji was moved"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "这个编号真的很长。", "这个编号真的很长。#️⃣", QwenTextPolisher.Mode.CHAT
            ),
            "A keycap emoji bypassed the allowlist"
        );
    }

    private static void keepsNewEmojiAtWholeMessageEndOnly() {
        String original = "哈哈这也太巧了。明天见。";
        String response = "{\"segments\":["
            + "{\"id\":1,\"text\":\"哈哈这也太巧了。\"},"
            + "{\"id\":2,\"text\":\"明天见。😂\"}]}";
        QwenTextPolisher polisher = polisherReturning(200, response, null);
        require(
            "哈哈这也太巧了。明天见。😂".equals(
                polisher.polishOrOriginal(original, QwenTextPolisher.Mode.CHAT)
            ),
            "Whole-message cue did not allow one final emoji"
        );

        String nonFinalResponse = "{\"segments\":["
            + "{\"id\":1,\"text\":\"哈哈这也太巧了。😂\"},"
            + "{\"id\":2,\"text\":\"明天见。\"}]}";
        QwenTextPolisher nonFinal = polisherReturning(200, nonFinalResponse, null);
        require(
            original.equals(nonFinal.polishOrOriginal(original, QwenTextPolisher.Mode.CHAT)),
            "A non-final segment gained an emoji"
        );

        String alreadyHasEmoji = "太好了🎉。明天没问题。";
        String duplicateResponse = "{\"segments\":["
            + "{\"id\":1,\"text\":\"太好了🎉。\"},"
            + "{\"id\":2,\"text\":\"明天没问题。👍\"}]}";
        QwenTextPolisher duplicate = polisherReturning(200, duplicateResponse, null);
        require(
            alreadyHasEmoji.equals(duplicate.polishOrOriginal(alreadyHasEmoji, QwenTextPolisher.Mode.CHAT)),
            "A message with an existing emoji gained another one"
        );
    }

    private static void keepsMixedTerminalClusterAtomic() {
        String original = "哈哈真的假的？！";
        var segments = QwenTextPolisher.segmentText(original, QwenTextPolisher.Mode.CHAT);
        require(segments.size() == 1, "Mixed terminal cluster was split into multiple segments");
        require(original.equals(segments.get(0).text()), "Mixed terminal cluster changed during segmentation");

        QwenTextPolisher polisher = polisherReturning(200, "哈哈真的假的？！😂", null);
        require(
            "哈哈真的假的？！😂".equals(
                polisher.polishOrOriginal(original, QwenTextPolisher.Mode.CHAT)
            ),
            "Mixed terminal cluster could not carry one final emoji"
        );
    }

    private static void preservesSegmentBoundariesAndAsrProsody() {
        String original = "第一句。第二句。";
        String response = "{\"segments\":["
            + "{\"id\":1,\"text\":\"第一句\"},"
            + "{\"id\":2,\"text\":\"第二句。\"}]}";
        QwenTextPolisher droppedBoundary = polisherReturning(200, response, null);
        require(
            original.equals(droppedBoundary.polishOrOriginal(original, QwenTextPolisher.Mode.CHAT)),
            "A non-final sentence boundary was dropped"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("你明天来吗？", "你明天来吗。", QwenTextPolisher.Mode.CHAT),
            "Question prosody was flattened"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("太好了！", "太好了。", QwenTextPolisher.Mode.CHAT),
            "Exclamation prosody was flattened"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("我再想想……", "我再想想。", QwenTextPolisher.Mode.CHAT),
            "Ellipsis prosody was flattened"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("我再想想。", "我再想想...", QwenTextPolisher.Mode.CHAT),
            "A new ASCII ellipsis was accepted"
        );
    }

    private static void preservesWorkCommaSegmentBoundaries() {
        String original = "这是一个足够长的工作场景语音片段，需要先把背景和限制条件完整说明清楚，然后再给出结论。";
        String response = "{\"segments\":["
            + "{\"id\":1,\"text\":\"这是一个足够长的工作场景语音片段，需要先把背景和限制条件完整说明清楚\"},"
            + "{\"id\":2,\"text\":\"然后再给出结论。\"}]}";
        QwenTextPolisher droppedComma = polisherReturning(200, response, null);
        require(
            original.equals(droppedComma.polishOrOriginal(original, QwenTextPolisher.Mode.WORK)),
            "A WORK comma segment boundary was dropped"
        );
    }

    private static void preservesInternalExpressiveBoundaries() {
        String chinese = "我有点……还是明天再说吧。";
        String chineseResponse = "{\"segments\":["
            + "{\"id\":1,\"text\":\"我有点\"},"
            + "{\"id\":2,\"text\":\"还是明天再说吧。\"}]}";
        QwenTextPolisher droppedChineseEllipsis = polisherReturning(200, chineseResponse, null);
        require(
            chinese.equals(droppedChineseEllipsis.polishOrOriginal(chinese, QwenTextPolisher.Mode.CHAT)),
            "An internal Chinese ellipsis was dropped"
        );

        String english = "Are you coming? Great!";
        String englishResponse = "{\"segments\":["
            + "{\"id\":1,\"text\":\"Are you coming\"},"
            + "{\"id\":2,\"text\":\"Great!\"}]}";
        QwenTextPolisher droppedAsciiQuestion = polisherReturning(200, englishResponse, null);
        require(
            english.equals(droppedAsciiQuestion.polishOrOriginal(english, QwenTextPolisher.Mode.CHAT)),
            "An internal ASCII question mark was dropped"
        );

        String asciiEllipsis = "I am not sure... maybe tomorrow.";
        String asciiResponse = "{\"segments\":["
            + "{\"id\":1,\"text\":\"I am not sure\"},"
            + "{\"id\":2,\"text\":\"maybe tomorrow.\"}]}";
        QwenTextPolisher droppedAsciiEllipsis = polisherReturning(200, asciiResponse, null);
        require(
            asciiEllipsis.equals(
                droppedAsciiEllipsis.polishOrOriginal(asciiEllipsis, QwenTextPolisher.Mode.CHAT)
            ),
            "An internal ASCII ellipsis was dropped"
        );
        require(
            !QwenTextPolisher.isSafeRewrite("我再想想。", "我再想想…", QwenTextPolisher.Mode.CHAT),
            "A single Unicode ellipsis was accepted"
        );
        require(
            !QwenTextPolisher.isSafeRewrite(
                "我下午三点到家。", "我下午三点！到家。", QwenTextPolisher.Mode.CHAT
            ),
            "New internal expressive punctuation was accepted"
        );
        require(
            QwenTextPolisher.isSafeRewrite(
                "哈哈真的假的！？", "哈哈真的假的！？😂", QwenTextPolisher.Mode.CHAT
            ),
            "An unchanged common mixed ending could not carry an emoji"
        );
    }

    private static void preservesProtectedPersonalNames() {
        String original = "林知夏和周明远明天都来。";
        var protectedTerms = java.util.List.of("林知夏", "周明远");
        require(
            QwenTextPolisher.validateRewrite(
                original, original, QwenTextPolisher.Mode.CHAT, true, original, protectedTerms
            ).safe(),
            "Canonical personal names were rejected"
        );
        require(
            !QwenTextPolisher.validateRewrite(
                original, "林知夏和周明圆明天都来。", QwenTextPolisher.Mode.CHAT,
                true, original, protectedTerms
            ).safe(),
            "A protected personal name was changed"
        );
    }

    private static void keepsSafeSegmentsWhenAnotherSegmentIsRejected() {
        String original = "嗯嗯，这个这个方案可以。第二段没有问题。";
        String response = "{\"segments\":["
            + "{\"id\":1,\"text\":\"这个方案可行。\"},"
            + "{\"id\":2,\"text\":\"第二段存在问题。\"}]}";
        QwenTextPolisher polisher = polisherReturning(200, response, null);
        String actual = polisher.polishOrOriginal(original, QwenTextPolisher.Mode.WORK);
        require(
            "这个方案可行。第二段没有问题。".equals(actual),
            "Safe segment was not kept with local fallback: " + actual
        );
    }

    private static void selectsModeFromTargetApplication() {
        KeyboardInjector.TargetSnapshot wechat = target("Weixin.exe");
        KeyboardInjector.TargetSnapshot work = target("Feishu.exe");
        require(
            VoiceInputManager.selectPolishMode(wechat) == QwenTextPolisher.Mode.CHAT,
            "Weixin target did not select chat mode"
        );
        require(
            VoiceInputManager.selectPolishMode(work) == QwenTextPolisher.Mode.WORK,
            "Non-WeChat target did not select work mode"
        );
    }

    private static KeyboardInjector.TargetSnapshot target(String executable) {
        return new KeyboardInjector.TargetSnapshot(
            "target", 1, executable, "class", 1, 1, 1, 0, 0, 0, 0
        );
    }

    private static QwenTextPolisher polisherReturning(
        int status,
        String content,
        AtomicReference<HttpRequest> captured
    ) {
        QwenTextPolisher.Transport transport = request -> {
            if (captured != null) {
                captured.set(request);
            }
            String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
            byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            return new QwenTextPolisher.ApiResponse(status, body);
        };
        return new QwenTextPolisher(ModelConfig.getInstance(), transport, () -> "sk-test-key".toCharArray());
    }

    private static String readBody(HttpRequest request) throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                failure.set(throwable);
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });
        require(completed.await(1, java.util.concurrent.TimeUnit.SECONDS), "Timed out reading request body");
        if (failure.get() != null) {
            throw new IllegalStateException(failure.get());
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
