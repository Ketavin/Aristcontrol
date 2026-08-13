package com.example.ahakey.service;

/** Offline checks for the exact-once decisions in KeyboardInjector. */
public final class KeyboardInjectorPolicySmokeTest {

    private KeyboardInjectorPolicySmokeTest() {
    }

    public static void main(String[] args) {
        require(KeyboardInjector.isValidText("中文，English A. 😀"), "valid Unicode was rejected");
        require(!KeyboardInjector.isValidText("bad\0text"), "NUL text was accepted");
        require(!KeyboardInjector.isValidText("bad\uD83D"), "isolated high surrogate was accepted");
        require(!KeyboardInjector.isValidText("bad\uDE00"), "isolated low surrogate was accepted");

        require(KeyboardInjector.isUnicodeFallbackSafe("AhaKey，Codex。"), "single-line fallback was rejected");
        require(!KeyboardInjector.isUnicodeFallbackSafe("line1\nline2"), "newline fallback could become Enter");
        require(!KeyboardInjector.isUnicodeFallbackSafe("a\tb"), "tab fallback could move focus");

        require(KeyboardInjector.mayFallbackToUnicode(false, 0), "pre-clipboard failure must allow fallback");
        require(KeyboardInjector.mayFallbackToUnicode(true, 0), "zero queued paste events must allow fallback");
        require(!KeyboardInjector.mayFallbackToUnicode(true, 1), "partial paste must never duplicate text");
        require(!KeyboardInjector.mayFallbackToUnicode(true, 4), "fully queued paste must never duplicate text");

        KeyboardInjector.TargetSnapshot weixinAtRecord = target(
            "微信", 3388, "Weixin.exe", "Qt51514QWindowIcon",
            0x100, 0x110, 0x120, 4, 8, 5, 24
        );
        KeyboardInjector.TargetSnapshot weixinAfterHud = target(
            "微信", 3388, "Weixin.exe", "Qt51514QWindowIcon",
            0x100, 0x100, 0, 0, 0, 0, 0
        );
        require(
            KeyboardInjector.compareTargetSnapshots(weixinAtRecord, weixinAfterHud, true)
                == KeyboardInjector.TargetMatch.MATCH,
            "same Weixin Qt main window must tolerate unstable child focus/caret metadata"
        );
        require(
            KeyboardInjector.compareTargetSnapshots(
                weixinAtRecord,
                target("微信", 3388, "Weixin.exe", "Qt51514QWindowIcon", 0x200, 0x100, 0, 0, 0, 0, 0),
                true
            ) == KeyboardInjector.TargetMatch.TOP_LEVEL_CHANGED,
            "another Weixin main window must remain blocked"
        );
        require(
            KeyboardInjector.compareTargetSnapshots(
                weixinAtRecord,
                target("微信", 3399, "Weixin.exe", "Qt51514QWindowIcon", 0x100, 0x100, 0, 0, 0, 0, 0),
                true
            ) == KeyboardInjector.TargetMatch.PROCESS_CHANGED,
            "a process change must remain blocked"
        );

        KeyboardInjector.TargetSnapshot ordinaryEditor = target(
            "Editor", 77, "editor.exe", "EditWindow", 0x300, 0x310, 0x320, 1, 2, 3, 4
        );
        require(
            KeyboardInjector.compareTargetSnapshots(
                ordinaryEditor,
                target("Editor", 77, "editor.exe", "EditWindow", 0x300, 0x311, 0x320, 1, 2, 3, 4),
                true
            ) == KeyboardInjector.TargetMatch.FOCUSED_WINDOW_CHANGED,
            "ordinary editors must keep strict child-focus protection"
        );

        require(
            KeyboardInjector.classifyOwnership(7, 7, true, true)
                == KeyboardInjector.OwnershipState.OWNED,
            "matching sequence and token must be owned"
        );
        require(
            KeyboardInjector.classifyOwnership(7, 8, false, false)
                == KeyboardInjector.OwnershipState.CHANGED,
            "sequence change must win over a transient open failure"
        );
        require(
            KeyboardInjector.classifyOwnership(7, 7, false, false)
                == KeyboardInjector.OwnershipState.BUSY,
            "same sequence plus open failure must stay busy, not changed"
        );
        require(
            KeyboardInjector.classifyOwnership(7, 7, true, false)
                == KeyboardInjector.OwnershipState.CHANGED,
            "missing ownership token must not be restored over"
        );

        KeyboardInjector injector = new KeyboardInjector();
        try {
            require(injector.awaitClipboardReady(2_000), "clipboard STA did not become ready");
        } finally {
            injector.release();
        }

        System.out.println("Keyboard injector policy smoke test passed");
    }

    private static KeyboardInjector.TargetSnapshot target(
        String title,
        int pid,
        String executable,
        String windowClass,
        long topLevelHwnd,
        long focusedHwnd,
        long caretHwnd,
        int caretLeft,
        int caretTop,
        int caretRight,
        int caretBottom
    ) {
        return new KeyboardInjector.TargetSnapshot(
            title,
            pid,
            executable,
            windowClass,
            topLevelHwnd,
            focusedHwnd,
            caretHwnd,
            caretLeft,
            caretTop,
            caretRight,
            caretBottom
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
