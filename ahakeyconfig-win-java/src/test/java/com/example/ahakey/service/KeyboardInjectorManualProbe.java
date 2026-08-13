package com.example.ahakey.service;

/**
 * Manual exact-text probe for representative Windows input controls.
 *
 * <p>Run this class, focus a blank test control during the countdown, and then
 * compare the control contents with {@link #DEFAULT_TEXT}. It intentionally
 * contains Chinese punctuation, ASCII punctuation, an English product name,
 * and a supplementary Unicode character.</p>
 */
public final class KeyboardInjectorManualProbe {

    static final String DEFAULT_TEXT = "甲，乙。ChatGPT，Codex。AhaKey 507C 😀";

    private KeyboardInjectorManualProbe() {
    }

    public static void main(String[] args) throws Exception {
        int delayMs = args.length > 0 ? Integer.parseInt(args[0]) : 2_000;
        String text = DEFAULT_TEXT;
        String expectedWindowTitle = null;
        if (args.length > 1 && args[1].startsWith("--title=")) {
            expectedWindowTitle = args[1].substring("--title=".length());
        } else if (args.length > 1) {
            text = args[1];
            expectedWindowTitle = args.length > 2 ? args[2] : null;
        }
        Thread.sleep(delayMs);

        KeyboardInjector injector = new KeyboardInjector();
        try {
            KeyboardInjector.TargetSnapshot target = injector.captureTargetSnapshot();
            if (expectedWindowTitle != null && !expectedWindowTitle.equals(target.title())) {
                throw new IllegalStateException("refused unexpected target: " + target.title());
            }
            KeyboardInjector.InjectionResult result = injector.injectText(
                text,
                target,
                nativeCommit -> {
                    nativeCommit.run();
                    return true;
                }
            );
            System.out.println("INJECTION_RESULT=" + result);
            System.out.println("EXPECTED_TEXT=" + text);
            // Keep the long-lived clipboard STA alive until its scheduled
            // restoration has completed, matching production lifecycle.
            Thread.sleep(2_000);
        } finally {
            injector.release();
        }
    }
}
