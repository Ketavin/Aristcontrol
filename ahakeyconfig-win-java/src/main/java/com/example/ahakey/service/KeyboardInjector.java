package com.example.ahakey.service;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * Windows text injector.
 *
 * <p>The primary path borrows the clipboard for one short transaction and asks
 * the target control to paste the whole string. This gives browser, Office,
 * chat, Electron and native editors one atomic text operation instead of a
 * stream of VK_PACKET events. The original OLE IDataObject is retained so all
 * clipboard formats (not only plain text) can be restored.</p>
 *
 * <p>Unicode SendInput is a fallback only while it is still certain that no
 * paste shortcut reached the target. Once any paste event may have entered the
 * input queue, retrying the text would risk a duplicate.</p>
 */
public class KeyboardInjector {

    private static final Logger logger = LoggerFactory.getLogger(KeyboardInjector.class);

    private static final int POST_RECORD_DELAY_MS = 300;
    // Keep the temporary text available long enough for a briefly busy
    // Electron/Office/RDP target to consume Ctrl+V. Restoration is scheduled
    // on the STA, so this does not add 1.5 seconds to the recognition callback.
    private static final int CLIPBOARD_LEASE_MS = 1_500;
    private static final int CLIPBOARD_OPEN_TIMEOUT_MS = 300;
    private static final int RESTORE_RETRY_MS = 750;
    private static final int SHUTDOWN_RESTORE_RETRY_MS = 1_500;
    private static final int MODIFIER_RELEASE_TIMEOUT_MS = 250;
    private static final int UNICODE_BATCH_CODE_UNITS = 128;
    private static final int PM_REMOVE = 0x0001;

    private static final int KEYEVENTF_KEYUP = 0x0002;
    private static final int KEYEVENTF_UNICODE = 0x0004;
    private static final int VK_CONTROL = 0x11;
    private static final int VK_SHIFT = 0x10;
    private static final int VK_MENU = 0x12;
    private static final int VK_LWIN = 0x5B;
    private static final int VK_RWIN = 0x5C;
    private static final int VK_V = 0x56;

    private static final int CF_UNICODETEXT = 13;
    private static final int GMEM_MOVEABLE = 0x0002;
    private static final int GMEM_ZEROINIT = 0x0040;
    private static final String TOKEN_FORMAT_NAME = "AhaKey.TextInjection.Token.v1";
    private static final String HISTORY_FORMAT_NAME = "CanIncludeInClipboardHistory";
    private static final String CLOUD_FORMAT_NAME = "CanUploadToCloudClipboard";
    private static final String MONITOR_FORMAT_NAME = "ExcludeClipboardContentFromMonitorProcessing";

    private final User32 user32;
    private final ClipboardUser32 clipboardUser32;
    private final ClipboardKernel32 clipboardKernel32;
    private final OleClipboard oleClipboard;
    private final StaWorker staWorker;

    public KeyboardInjector() {
        user32 = User32.INSTANCE;
        clipboardUser32 = ClipboardUser32.INSTANCE;
        clipboardKernel32 = ClipboardKernel32.INSTANCE;
        oleClipboard = OleClipboard.INSTANCE;
        staWorker = new StaWorker();
    }

    /** Waits for the clipboard STA, OLE apartment and hidden owner window. */
    public boolean awaitClipboardReady(long timeoutMillis) {
        try {
            staWorker.initialized.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            WinDef.HWND owner = staWorker.ownerWindow;
            return staWorker.oleReady
                && owner != null
                && Pointer.nativeValue(owner.getPointer()) != 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            logger.error("KeyboardInjector - clipboard STA 未就绪: {}", e.getMessage());
            return false;
        }
    }

    public enum InjectionResult {
        CLIPBOARD_ENQUEUED,
        CLIPBOARD_ENQUEUED_RESTORE_PENDING,
        UNICODE_ENQUEUED,
        PARTIAL_UNKNOWN,
        PARTIAL_UNKNOWN_RESTORE_PENDING,
        MODIFIERS_ACTIVE,
        INVALID_TEXT,
        CANCELLED,
        FAILED_RESTORE_PENDING,
        FAILED
    }

    enum TargetMatch {
        MATCH,
        INVALID_EXPECTED_TARGET,
        TOP_LEVEL_CHANGED,
        PROCESS_CHANGED,
        FOCUSED_WINDOW_CHANGED,
        CARET_WINDOW_CHANGED,
        CARET_POSITION_CHANGED
    }

    /**
     * Foreground input context captured before the recording HUD is shown.
     * Native child focus/caret handles let us reject most same-window focus
     * changes as well as ordinary top-level window switches.
     */
    public record TargetSnapshot(
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
        boolean isUsable() {
            return topLevelHwnd != 0;
        }
    }

    @FunctionalInterface
    public interface CommitGate {
        boolean commitIfCurrent(Runnable nativeCommit);
    }

    /** Inject text into the currently focused target. */
    public InjectionResult injectText(String text) {
        return injectText(text, null, nativeCommit -> {
            nativeCommit.run();
            return true;
        });
    }

    /** Inject with an atomic session/cancellation check around every native commit. */
    public synchronized InjectionResult injectText(String text, CommitGate commitGate) {
        return injectText(text, null, commitGate);
    }

    /** Inject only if the input context captured at recording start is still active. */
    public synchronized InjectionResult injectText(
        String text,
        TargetSnapshot expectedTarget,
        CommitGate commitGate
    ) {
        if (!isValidText(text)) {
            logger.warn("KeyboardInjector - 拒绝空文本、NUL 或无效 Unicode 文本");
            return InjectionResult.INVALID_TEXT;
        }

        logger.debug("KeyboardInjector - 开始注入文本: \"{}\"", text);
        try {
            Thread.sleep(POST_RECORD_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return InjectionResult.FAILED;
        }

        if (commitGate == null || !commitGate.commitIfCurrent(() -> { })) {
            return InjectionResult.CANCELLED;
        }

        try {
            return staWorker.call(() -> injectTextOnSta(text, expectedTarget, commitGate));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("KeyboardInjector - 等待 STA 注入线程时被中断；任务不会被盲目重放");
            return InjectionResult.FAILED;
        } catch (ExecutionException | IllegalStateException e) {
            logger.error("KeyboardInjector - STA 注入线程失败: {}", e.getMessage(), e);
            return InjectionResult.FAILED;
        }
    }

    private InjectionResult injectTextOnSta(
        String text,
        TargetSnapshot expectedTarget,
        CommitGate commitGate
    ) {
        if (!staWorker.ensurePendingSettled()) {
            logger.error("KeyboardInjector - 上一次原剪贴板仍待恢复，本次注入 fail closed");
            return InjectionResult.FAILED_RESTORE_PENDING;
        }

        TargetSnapshot target = expectedTarget != null ? expectedTarget : captureTargetSnapshot();
        logger.info(
            "KeyboardInjector - 目标窗口: title=\"{}\", pid={}, exe={}, class={}, hwnd=0x{}",
            target.title,
            target.pid,
            target.executable,
            target.windowClass,
            Long.toHexString(target.topLevelHwnd)
        );

        if (!target.isUsable()) {
            logger.error("KeyboardInjector - 前台目标 hwnd=0，拒绝向未知窗口注入");
            return InjectionResult.FAILED;
        }

        TargetMatch initialMatch = matchForegroundTarget(target, true);
        if (initialMatch != TargetMatch.MATCH) {
            loggerTargetMismatch("录音开始后的输入目标已变化，取消文本注入", initialMatch, target);
            return InjectionResult.CANCELLED;
        }

        if (!waitForModifiersReleased()) {
            logger.warn("KeyboardInjector - Ctrl/Shift/Alt/Win 仍被按住，停止注入以避免组合键串扰");
            return InjectionResult.MODIFIERS_ACTIVE;
        }

        if (!commitGate.commitIfCurrent(() -> { })) {
            return InjectionResult.CANCELLED;
        }

        ClipboardPreparation preparation = beginClipboardLease(text, commitGate);
        if (preparation.cancelled) {
            return InjectionResult.CANCELLED;
        }
        ClipboardLease lease = preparation.lease;
        if (lease == null && preparation.fallbackAllowed) {
            logger.warn("KeyboardInjector - 无法安全借用并完整恢复剪贴板，尝试 Unicode 回退");
            return injectUnicodeFallback(text, target, commitGate);
        }
        if (lease == null) {
            logger.error("KeyboardInjector - 剪贴板事务失败且原内容仍待恢复，停止注入");
            return preparation.restorePending
                ? InjectionResult.FAILED_RESTORE_PENDING
                : InjectionResult.FAILED;
        }

        TargetMatch prePasteMatch = matchForegroundTarget(target, true);
        if (prePasteMatch != TargetMatch.MATCH) {
            RestoreOutcome restore = lease.restoreImmediately();
            loggerTargetMismatch("注入前目标已变化，停止向未知窗口发送文本", prePasteMatch, target);
            return restore.needsAttention()
                ? InjectionResult.FAILED_RESTORE_PENDING
                : InjectionResult.FAILED;
        }

        int expected = 4;
        int[] sentHolder = new int[1];
        if (!commitGate.commitIfCurrent(() -> sentHolder[0] = sendPasteShortcut())) {
            RestoreOutcome restore = lease.restoreImmediately();
            return restore.needsAttention()
                ? InjectionResult.FAILED_RESTORE_PENDING
                : InjectionResult.CANCELLED;
        }
        int sent = sentHolder[0];
        logger.info("KeyboardInjector - clipboard paste SendInput: sent={}/{}", sent, expected);

        if (sent == 0) {
            RestoreOutcome outcome = lease.restoreImmediately();
            if (outcome.needsAttention()) {
                logger.error("KeyboardInjector - Ctrl+V 未入队但原剪贴板仍待恢复，停止后续输入");
                return InjectionResult.FAILED_RESTORE_PENDING;
            }
            logger.warn("KeyboardInjector - Ctrl+V 未有事件入队，安全回退 Unicode");
            return injectUnicodeFallback(text, target, commitGate);
        }

        if (sent < expected) {
            releasePasteModifiers();
            RestoreOutcome restore = lease.restoreAfterDelay();
            logger.error("KeyboardInjector - Ctrl+V 部分入队，结果未知；不会二次注入文本");
            return restore.needsAttention()
                ? InjectionResult.PARTIAL_UNKNOWN_RESTORE_PENDING
                : InjectionResult.PARTIAL_UNKNOWN;
        }

        RestoreOutcome restore = lease.restoreAfterDelay();
        InjectionResult result = restore.needsAttention()
            ? InjectionResult.CLIPBOARD_ENQUEUED_RESTORE_PENDING
            : InjectionResult.CLIPBOARD_ENQUEUED;
        logger.info(
            "KeyboardInjector - method=clipboard events={}/{} restore={} result={} title=\"{}\" pid={} exe={} class={}",
            sent, expected, restore, result, logSafe(target.title), target.pid,
            target.executable, target.windowClass
        );
        return result;
    }

    private TargetMatch matchForegroundTarget(TargetSnapshot target, boolean compareCaretPosition) {
        TargetSnapshot current = captureTargetSnapshot();
        return compareTargetSnapshots(target, current, compareCaretPosition);
    }

    static TargetMatch compareTargetSnapshots(
        TargetSnapshot expected,
        TargetSnapshot current,
        boolean compareCaretPosition
    ) {
        if (expected == null || !expected.isUsable()) {
            return TargetMatch.INVALID_EXPECTED_TARGET;
        }
        if (current == null || current.topLevelHwnd != expected.topLevelHwnd) {
            return TargetMatch.TOP_LEVEL_CHANGED;
        }
        if (current.pid != expected.pid) {
            return TargetMatch.PROCESS_CHANGED;
        }

        // Weixin renders its editor through a Qt virtualized top-level window.
        // Showing an overlay or changing the IME state can make
        // GetGUIThreadInfo report different focus/caret metadata even though
        // the same Weixin main window is still foreground. The top-level HWND
        // and PID remain stable and are the reliable safety boundary here.
        if (isWeixinQtTarget(expected)) {
            return TargetMatch.MATCH;
        }
        if (expected.focusedHwnd != 0 && current.focusedHwnd != expected.focusedHwnd) {
            return TargetMatch.FOCUSED_WINDOW_CHANGED;
        }
        if (expected.caretHwnd != 0 && current.caretHwnd != expected.caretHwnd) {
            return TargetMatch.CARET_WINDOW_CHANGED;
        }
        if (compareCaretPosition
            && expected.caretHwnd != 0
            && (current.caretLeft != expected.caretLeft
                || current.caretTop != expected.caretTop
                || current.caretRight != expected.caretRight
                || current.caretBottom != expected.caretBottom)) {
            return TargetMatch.CARET_POSITION_CHANGED;
        }
        return TargetMatch.MATCH;
    }

    static boolean isWeixinQtTarget(TargetSnapshot target) {
        if (target == null || target.executable == null || target.windowClass == null) {
            return false;
        }
        boolean weixinExecutable = "Weixin.exe".equalsIgnoreCase(target.executable)
            || "WeChat.exe".equalsIgnoreCase(target.executable);
        return weixinExecutable && target.windowClass.regionMatches(true, 0, "Qt", 0, 2);
    }

    private void loggerTargetMismatch(String message, TargetMatch mismatch, TargetSnapshot expected) {
        TargetSnapshot current = captureTargetSnapshot();
        logger.warn(
            "KeyboardInjector - {}: reason={} expected[top=0x{},pid={},focus=0x{},caret=0x{},rect={}] "
                + "current[top=0x{},pid={},focus=0x{},caret=0x{},rect={}]",
            message,
            mismatch,
            Long.toHexString(expected.topLevelHwnd),
            expected.pid,
            Long.toHexString(expected.focusedHwnd),
            Long.toHexString(expected.caretHwnd),
            caretRect(expected),
            Long.toHexString(current.topLevelHwnd),
            current.pid,
            Long.toHexString(current.focusedHwnd),
            Long.toHexString(current.caretHwnd),
            caretRect(current)
        );
    }

    private static String caretRect(TargetSnapshot target) {
        return target.caretLeft + "," + target.caretTop + ","
            + target.caretRight + "," + target.caretBottom;
    }

    private String logSafe(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    public TargetSnapshot captureTargetSnapshot() {
        WinDef.HWND hwnd = user32.GetForegroundWindow();
        if (hwnd == null) {
            return emptyTargetSnapshot();
        }

        char[] titleBuffer = new char[512];
        user32.GetWindowText(hwnd, titleBuffer, titleBuffer.length);
        String title = Native.toString(titleBuffer);

        char[] classBuffer = new char[256];
        user32.GetClassName(hwnd, classBuffer, classBuffer.length);
        String windowClass = Native.toString(classBuffer);

        IntByReference pidRef = new IntByReference();
        int guiThreadId = user32.GetWindowThreadProcessId(hwnd, pidRef);
        int pid = pidRef.getValue();
        String executable = "unknown";
        try {
            executable = ProcessHandle.of(pid)
                .flatMap(process -> process.info().command())
                .map(command -> {
                    try {
                        return Path.of(command).getFileName().toString();
                    } catch (Exception ignored) {
                        return command;
                    }
                })
                .orElse("unknown");
        } catch (RuntimeException e) {
            logger.debug("KeyboardInjector - 无法读取目标进程路径: {}", e.getMessage());
        }
        long focusedHwnd = 0;
        long caretHwnd = 0;
        int caretLeft = 0;
        int caretTop = 0;
        int caretRight = 0;
        int caretBottom = 0;
        if (guiThreadId != 0) {
            WinUser.GUITHREADINFO guiInfo = new WinUser.GUITHREADINFO();
            guiInfo.cbSize = guiInfo.size();
            if (user32.GetGUIThreadInfo(guiThreadId, guiInfo)) {
                focusedHwnd = pointerValue(guiInfo.hwndFocus);
                caretHwnd = pointerValue(guiInfo.hwndCaret);
                caretLeft = guiInfo.rcCaret.left;
                caretTop = guiInfo.rcCaret.top;
                caretRight = guiInfo.rcCaret.right;
                caretBottom = guiInfo.rcCaret.bottom;
            }
        }
        long hwndValue = Pointer.nativeValue(hwnd.getPointer());
        return new TargetSnapshot(
            title, pid, executable, windowClass, hwndValue, focusedHwnd, caretHwnd,
            caretLeft, caretTop, caretRight, caretBottom
        );
    }

    private TargetSnapshot emptyTargetSnapshot() {
        return new TargetSnapshot("", 0, "unknown", "unknown", 0, 0, 0, 0, 0, 0, 0);
    }

    private long pointerValue(WinDef.HWND hwnd) {
        return hwnd == null ? 0 : Pointer.nativeValue(hwnd.getPointer());
    }

    private boolean waitForModifiersReleased() {
        long deadline = System.nanoTime() + MODIFIER_RELEASE_TIMEOUT_MS * 1_000_000L;
        while (hasActiveModifier()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private boolean hasActiveModifier() {
        return isPhysicallyDown(VK_CONTROL)
            || isPhysicallyDown(VK_SHIFT)
            || isPhysicallyDown(VK_MENU)
            || isPhysicallyDown(VK_LWIN)
            || isPhysicallyDown(VK_RWIN);
    }

    private boolean isPhysicallyDown(int vk) {
        return (user32.GetAsyncKeyState(vk) & 0x8000) != 0;
    }

    private ClipboardPreparation beginClipboardLease(String text, CommitGate commitGate) {
        WinDef.HWND owner = staWorker.ownerWindow;
        if (!staWorker.oleReady || owner == null
            || Pointer.nativeValue(owner.getPointer()) == 0) {
            logger.warn("KeyboardInjector - STA OLE/owner window 不可用，剪贴板路径 fail closed");
            return ClipboardPreparation.fallback();
        }

        Pointer original = null;
        try {
            int originalSequence = clipboardUser32.GetClipboardSequenceNumber();
            PointerByReference originalRef = new PointerByReference();
            WinNT.HRESULT getResult = oleClipboard.OleGetClipboard(originalRef);
            if (!COMUtils.SUCCEEDED(getResult)) {
                logger.warn("KeyboardInjector - OleGetClipboard 失败，拒绝破坏现有剪贴板: 0x{}",
                    Long.toHexString(getResult.longValue()));
                return ClipboardPreparation.fallback();
            }
            original = originalRef.getValue();
            if (clipboardUser32.GetClipboardSequenceNumber() != originalSequence) {
                releaseOriginal(original);
                logger.info("KeyboardInjector - 捕获剪贴板期间内容已变化，改用安全回退");
                return ClipboardPreparation.fallback();
            }

            String token = UUID.randomUUID().toString();
            int tokenFormat = clipboardUser32.RegisterClipboardFormat(TOKEN_FORMAT_NAME);
            if (tokenFormat == 0) {
                releaseOriginal(original);
                return ClipboardPreparation.fallback();
            }
            TemporaryWrite[] writeHolder = new TemporaryWrite[1];
            if (!commitGate.commitIfCurrent(() -> writeHolder[0] = writeTemporaryClipboard(
                owner, text, token, tokenFormat, originalSequence
            ))) {
                releaseOriginal(original);
                return ClipboardPreparation.cancelledPreparation();
            }
            TemporaryWrite write = writeHolder[0];
            if (write.outcome == ClipboardWriteOutcome.FAILED_BEFORE_CHANGE) {
                releaseOriginal(original);
                return ClipboardPreparation.fallback();
            }

            ClipboardLease lease = new ClipboardLease(
                original, write.sequence, tokenFormat, token, write.tokenInstalled
            );
            if (write.outcome == ClipboardWriteOutcome.SUCCESS) {
                return ClipboardPreparation.ready(lease);
            }

            RestoreOutcome restore = lease.restoreImmediately();
            return restore.needsAttention()
                ? ClipboardPreparation.stopWithPendingRestore()
                : ClipboardPreparation.fallback();
        } catch (Exception e) {
            logger.error("KeyboardInjector - 建立剪贴板事务失败: {}", e.getMessage());
            releaseOriginal(original);
            return ClipboardPreparation.fallback();
        }
    }

    private TemporaryWrite writeTemporaryClipboard(
        WinDef.HWND owner,
        String text,
        String token,
        int tokenFormat,
        int expectedOriginalSequence
    ) {
        Pointer textHandle = allocateWideString(text);
        Pointer tokenHandle = allocateWideString(token);
        Pointer historyHandle = allocateDword(0);
        Pointer cloudHandle = allocateDword(0);
        Pointer monitorHandle = allocateDword(1);
        if (textHandle == null || tokenHandle == null || historyHandle == null
            || cloudHandle == null || monitorHandle == null) {
            freeGlobal(textHandle);
            freeGlobal(tokenHandle);
            freeGlobal(historyHandle);
            freeGlobal(cloudHandle);
            freeGlobal(monitorHandle);
            return TemporaryWrite.beforeChangeFailure();
        }

        if (!openClipboard(owner)) {
            freeGlobal(textHandle);
            freeGlobal(tokenHandle);
            freeGlobal(historyHandle);
            freeGlobal(cloudHandle);
            freeGlobal(monitorHandle);
            return TemporaryWrite.beforeChangeFailure();
        }

        boolean textOwned = false;
        boolean tokenOwned = false;
        boolean historyOwned = false;
        boolean cloudOwned = false;
        boolean monitorOwned = false;
        boolean clipboardEmptied = false;
        boolean success = false;
        try {
            if (clipboardUser32.GetClipboardSequenceNumber() == expectedOriginalSequence) {
                clipboardEmptied = clipboardUser32.EmptyClipboard();
            }
            if (clipboardEmptied) {
                // Install the ownership token first. If a later format fails we can still
                // prove that the temporary clipboard is ours before restoring IDataObject.
                tokenOwned = clipboardUser32.SetClipboardData(tokenFormat, tokenHandle) != null;
            }
            if (tokenOwned) {
                textOwned = clipboardUser32.SetClipboardData(CF_UNICODETEXT, textHandle) != null;
            }
            if (textOwned) {
                int historyFormat = clipboardUser32.RegisterClipboardFormat(HISTORY_FORMAT_NAME);
                int cloudFormat = clipboardUser32.RegisterClipboardFormat(CLOUD_FORMAT_NAME);
                int monitorFormat = clipboardUser32.RegisterClipboardFormat(MONITOR_FORMAT_NAME);
                if (historyFormat != 0) {
                    historyOwned = clipboardUser32.SetClipboardData(historyFormat, historyHandle) != null;
                }
                if (cloudFormat != 0) {
                    cloudOwned = clipboardUser32.SetClipboardData(cloudFormat, cloudHandle) != null;
                }
                if (monitorFormat != 0) {
                    monitorOwned = clipboardUser32.SetClipboardData(monitorFormat, monitorHandle) != null;
                }
                // Privacy hint formats are best effort; text + token define the transaction.
                success = tokenOwned && textOwned;
            }
        } finally {
            clipboardUser32.CloseClipboard();
            if (!textOwned) freeGlobal(textHandle);
            if (!tokenOwned) freeGlobal(tokenHandle);
            if (!historyOwned) freeGlobal(historyHandle);
            if (!cloudOwned) freeGlobal(cloudHandle);
            if (!monitorOwned) freeGlobal(monitorHandle);
        }
        int sequence = clipboardUser32.GetClipboardSequenceNumber();
        if (success) {
            return new TemporaryWrite(ClipboardWriteOutcome.SUCCESS, sequence, true);
        }
        return clipboardEmptied
            ? new TemporaryWrite(ClipboardWriteOutcome.FAILED_AFTER_CHANGE, sequence, tokenOwned)
            : TemporaryWrite.beforeChangeFailure();
    }

    private Pointer allocateWideString(String value) {
        long bytes = ((long) value.length() + 1L) * Native.WCHAR_SIZE;
        Pointer handle = clipboardKernel32.GlobalAlloc(
            GMEM_MOVEABLE | GMEM_ZEROINIT,
            new BaseTSD.SIZE_T(bytes)
        );
        if (handle == null) {
            return null;
        }
        Pointer memory = clipboardKernel32.GlobalLock(handle);
        if (memory == null) {
            clipboardKernel32.GlobalFree(handle);
            return null;
        }
        try {
            memory.setWideString(0, value);
        } finally {
            clipboardKernel32.GlobalUnlock(handle);
        }
        return handle;
    }

    private Pointer allocateDword(int value) {
        Pointer handle = clipboardKernel32.GlobalAlloc(
            GMEM_MOVEABLE | GMEM_ZEROINIT,
            new BaseTSD.SIZE_T(4)
        );
        if (handle == null) {
            return null;
        }
        Pointer memory = clipboardKernel32.GlobalLock(handle);
        if (memory == null) {
            clipboardKernel32.GlobalFree(handle);
            return null;
        }
        try {
            memory.setInt(0, value);
        } finally {
            clipboardKernel32.GlobalUnlock(handle);
        }
        return handle;
    }

    private boolean openClipboard(WinDef.HWND owner) {
        return openClipboard(owner, CLIPBOARD_OPEN_TIMEOUT_MS);
    }

    private boolean openClipboard(WinDef.HWND owner, int timeoutMs) {
        long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
        do {
            if (clipboardUser32.OpenClipboard(owner)) {
                return true;
            }
            if (!staWorker.waitWithMessagePump(15)) {
                return false;
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    private int sendPasteShortcut() {
        WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(4);
        fillVirtualKey(inputs[0], VK_CONTROL, false);
        fillVirtualKey(inputs[1], VK_V, false);
        fillVirtualKey(inputs[2], VK_V, true);
        fillVirtualKey(inputs[3], VK_CONTROL, true);
        return user32.SendInput(new WinDef.DWORD(inputs.length), inputs, inputs[0].size()).intValue();
    }

    private void releasePasteModifiers() {
        WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(2);
        fillVirtualKey(inputs[0], VK_V, true);
        fillVirtualKey(inputs[1], VK_CONTROL, true);
        int sent = user32.SendInput(new WinDef.DWORD(inputs.length), inputs, inputs[0].size()).intValue();
        logger.warn("KeyboardInjector - 部分发送后的按键释放补偿: sent={}/{}", sent, inputs.length);
    }

    private InjectionResult injectUnicodeFallback(
        String text,
        TargetSnapshot target,
        CommitGate commitGate
    ) {
        if (!isUnicodeFallbackSafe(text)) {
            logger.error("KeyboardInjector - 文本包含换行/Tab/控制字符，拒绝将其变成动作按键");
            return InjectionResult.FAILED;
        }

        int offset = 0;
        while (offset < text.length()) {
            // Focus can change after clipboard preparation or between long Unicode
            // batches. Never continue sending into a newly focused control.
            if (matchForegroundTarget(target, offset == 0) != TargetMatch.MATCH) {
                logger.error(
                    "KeyboardInjector - Unicode batch 前目标已变化，offset={}；不会继续注入",
                    offset
                );
                return offset == 0 ? InjectionResult.FAILED : InjectionResult.PARTIAL_UNKNOWN;
            }
            int units = Math.min(UNICODE_BATCH_CODE_UNITS, text.length() - offset);
            WinUser.INPUT[] inputs = (WinUser.INPUT[]) new WinUser.INPUT().toArray(units * 2);
            int index = 0;
            for (int i = 0; i < units; i++) {
                char unit = text.charAt(offset + i);
                fillUnicode(inputs[index++], unit, false);
                fillUnicode(inputs[index++], unit, true);
            }
            int[] sentHolder = new int[1];
            if (!commitGate.commitIfCurrent(() -> sentHolder[0] = user32.SendInput(
                new WinDef.DWORD(inputs.length), inputs, inputs[0].size()
            ).intValue())) {
                return offset == 0 ? InjectionResult.CANCELLED : InjectionResult.PARTIAL_UNKNOWN;
            }
            int sent = sentHolder[0];
            logger.info(
                "KeyboardInjector - method=unicode events={}/{} batchOffset={} title=\"{}\" pid={} exe={} class={}",
                sent, inputs.length, offset, logSafe(target.title), target.pid,
                target.executable, target.windowClass
            );
            if (sent != inputs.length) {
                return sent == 0 && offset == 0
                    ? InjectionResult.FAILED
                    : InjectionResult.PARTIAL_UNKNOWN;
            }
            offset += units;
        }
        return InjectionResult.UNICODE_ENQUEUED;
    }

    private void fillVirtualKey(WinUser.INPUT input, int vk, boolean keyUp) {
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki.wVk = new WinDef.WORD(vk);
        input.input.ki.wScan = new WinDef.WORD(0);
        input.input.ki.dwFlags = new WinDef.DWORD(keyUp ? KEYEVENTF_KEYUP : 0);
    }

    private void fillUnicode(WinUser.INPUT input, char unit, boolean keyUp) {
        input.type = new WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD);
        input.input.setType("ki");
        input.input.ki.wVk = new WinDef.WORD(0);
        input.input.ki.wScan = new WinDef.WORD(unit);
        input.input.ki.dwFlags = new WinDef.DWORD(KEYEVENTF_UNICODE | (keyUp ? KEYEVENTF_KEYUP : 0));
    }

    static boolean isValidText(String text) {
        if (text == null || text.isEmpty() || text.indexOf('\0') >= 0) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    static boolean isUnicodeFallbackSafe(String text) {
        if (!isValidText(text)) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || (c < 0x20 && c != ' ')) {
                return false;
            }
        }
        return true;
    }

    static boolean mayFallbackToUnicode(boolean clipboardWasPrepared, int pasteEventsSent) {
        return !clipboardWasPrepared || pasteEventsSent == 0;
    }

    static OwnershipState classifyOwnership(
        int expectedSequence,
        int currentSequence,
        boolean clipboardOpened,
        boolean ownershipMarkerMatches
    ) {
        if (expectedSequence != currentSequence) {
            return OwnershipState.CHANGED;
        }
        if (!clipboardOpened) {
            return OwnershipState.BUSY;
        }
        return ownershipMarkerMatches ? OwnershipState.OWNED : OwnershipState.CHANGED;
    }

    private OwnershipState probeClipboardOwnership(ClipboardLease lease, int openTimeoutMs) {
        int beforeOpen = clipboardUser32.GetClipboardSequenceNumber();
        if (beforeOpen != lease.sequence) {
            return OwnershipState.CHANGED;
        }
        if (!openClipboard(staWorker.ownerWindow, openTimeoutMs)) {
            return OwnershipState.BUSY;
        }
        try {
            int whileOpen = clipboardUser32.GetClipboardSequenceNumber();
            if (whileOpen != lease.sequence) {
                return OwnershipState.CHANGED;
            }

            boolean markerMatches;
            if (lease.tokenInstalled) {
                markerMatches = tokenMatches(lease.tokenFormat, lease.token);
            } else {
                // EmptyClipboard makes the OpenClipboard hwnd the owner. This branch
                // exists only for a token SetClipboardData failure and avoids losing
                // the saved IDataObject during that tiny setup-failure window.
                WinDef.HWND currentOwner = clipboardUser32.GetClipboardOwner();
                long currentOwnerValue = currentOwner == null
                    ? 0
                    : Pointer.nativeValue(currentOwner.getPointer());
                long expectedOwnerValue = staWorker.ownerWindow == null
                    ? 0
                    : Pointer.nativeValue(staWorker.ownerWindow.getPointer());
                markerMatches = expectedOwnerValue != 0 && currentOwnerValue == expectedOwnerValue;
            }
            return classifyOwnership(lease.sequence, whileOpen, true, markerMatches);
        } finally {
            clipboardUser32.CloseClipboard();
        }
    }

    private boolean tokenMatches(int tokenFormat, String expectedToken) {
        Pointer handle = clipboardUser32.GetClipboardData(tokenFormat);
        if (handle == null) {
            return false;
        }
        BaseTSD.SIZE_T globalSize = clipboardKernel32.GlobalSize(handle);
        long required = ((long) expectedToken.length() + 1L) * Native.WCHAR_SIZE;
        if (globalSize == null || globalSize.longValue() < required || globalSize.longValue() > 1024) {
            return false;
        }
        Pointer memory = clipboardKernel32.GlobalLock(handle);
        if (memory == null) {
            return false;
        }
        try {
            for (int i = 0; i < expectedToken.length(); i++) {
                if (memory.getChar((long) i * Native.WCHAR_SIZE) != expectedToken.charAt(i)) {
                    return false;
                }
            }
            return memory.getChar((long) expectedToken.length() * Native.WCHAR_SIZE) == '\0';
        } finally {
            clipboardKernel32.GlobalUnlock(handle);
        }
    }

    private void releaseOriginal(Pointer original) {
        if (original != null) {
            try {
                new Unknown(original).Release();
            } catch (Exception e) {
                logger.warn("KeyboardInjector - 释放原剪贴板 IDataObject 失败: {}", e.getMessage());
            }
        }
    }

    private void freeGlobal(Pointer handle) {
        if (handle != null) {
            clipboardKernel32.GlobalFree(handle);
        }
    }

    public synchronized void release() {
        staWorker.shutdown();
    }

    private final class ClipboardLease {
        private final Pointer original;
        private final int sequence;
        private final int tokenFormat;
        private final String token;
        private final boolean tokenInstalled;
        private RestorePhase phase = RestorePhase.TEMPORARY;
        private RestoreOutcome terminalOutcome;
        private long restoreNotBeforeNanos;

        private ClipboardLease(
            Pointer original,
            int sequence,
            int tokenFormat,
            String token,
            boolean tokenInstalled
        ) {
            this.original = original;
            this.sequence = sequence;
            this.tokenFormat = tokenFormat;
            this.token = token;
            this.tokenInstalled = tokenInstalled;
        }

        private RestoreOutcome restoreImmediately() {
            restoreNotBeforeNanos = 0;
            RestoreOutcome outcome = attemptRestore(RESTORE_RETRY_MS);
            if (outcome.needsAttention()) {
                staWorker.retainPending(this);
            }
            return outcome;
        }

        private RestoreOutcome restoreAfterDelay() {
            restoreNotBeforeNanos = System.nanoTime() + CLIPBOARD_LEASE_MS * 1_000_000L;
            staWorker.retainPending(this);
            return RestoreOutcome.RESTORE_SCHEDULED;
        }

        private boolean restoreIsDue() {
            return restoreNotBeforeNanos == 0 || System.nanoTime() >= restoreNotBeforeNanos;
        }

        private int millisUntilRestoreDue() {
            if (restoreIsDue()) {
                return 0;
            }
            long remainingNanos = restoreNotBeforeNanos - System.nanoTime();
            long remainingMillisCeil = (remainingNanos + 999_999L) / 1_000_000L;
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, remainingMillisCeil));
        }

        private void makeRestoreDueNow() {
            restoreNotBeforeNanos = 0;
        }

        private RestoreOutcome attemptRestore(int timeoutMs) {
            if (terminalOutcome != null) {
                return terminalOutcome;
            }
            if (!restoreIsDue()) {
                return RestoreOutcome.RESTORE_SCHEDULED;
            }
            long deadline = System.nanoTime() + Math.max(0, timeoutMs) * 1_000_000L;
            boolean first = true;
            do {
                try {
                    if (phase == RestorePhase.TEMPORARY) {
                        OwnershipState ownership = probeClipboardOwnership(this, Math.min(50, timeoutMs));
                        if (ownership == OwnershipState.CHANGED) {
                            logger.info("KeyboardInjector - 剪贴板已被用户或目标应用更新，不覆盖新内容");
                            return finish(RestoreOutcome.CHANGED);
                        }
                        if (ownership == OwnershipState.OWNED) {
                            // The OpenClipboard/CloseClipboard contract makes an atomic
                            // compare-and-swap impossible. Recheck the sequence immediately
                            // before OleSetClipboard to minimize (not eliminate) the TOCTOU.
                            if (clipboardUser32.GetClipboardSequenceNumber() == sequence) {
                                WinNT.HRESULT set = oleClipboard.OleSetClipboard(original);
                                if (COMUtils.SUCCEEDED(set)) {
                                    phase = RestorePhase.FLUSHING;
                                    if (original == null) {
                                        return finish(RestoreOutcome.RESTORED);
                                    }
                                } else {
                                    logger.debug("KeyboardInjector - OleSetClipboard 暂未成功: 0x{}",
                                        Long.toHexString(set.longValue()));
                                }
                            }
                        }
                    }

                    if (phase == RestorePhase.FLUSHING) {
                        WinNT.HRESULT current = oleClipboard.OleIsCurrentClipboard(original);
                        if (current.intValue() == 1) { // S_FALSE: another owner replaced it.
                            return finish(RestoreOutcome.CHANGED);
                        }
                        if (current.intValue() == 0) {
                            WinNT.HRESULT flush = oleClipboard.OleFlushClipboard();
                            if (COMUtils.SUCCEEDED(flush)) {
                                logger.debug("KeyboardInjector - 原 IDataObject 已恢复并 OleFlushClipboard");
                                return finish(RestoreOutcome.RESTORED);
                            }
                            logger.debug("KeyboardInjector - OleFlushClipboard 暂未成功: 0x{}",
                                Long.toHexString(flush.longValue()));
                        }
                    }
                } catch (RuntimeException e) {
                    logger.warn("KeyboardInjector - 剪贴板恢复尝试异常，将保留 IDataObject 重试: {}",
                        e.getMessage());
                }

                first = false;
                if (System.nanoTime() < deadline && !staWorker.waitWithMessagePump(20)) {
                    break;
                }
            } while (first || System.nanoTime() < deadline);
            return RestoreOutcome.RESTORE_PENDING;
        }

        private RestoreOutcome finish(RestoreOutcome outcome) {
            terminalOutcome = outcome;
            phase = RestorePhase.FINISHED;
            releaseOriginal(original);
            return outcome;
        }

        private void abandonAtShutdown() {
            if (terminalOutcome == null) {
                logger.error("KeyboardInjector - 关闭时剪贴板仍 busy；为避免覆盖未知新内容，放弃恢复并释放 IDataObject");
                finish(RestoreOutcome.RESTORE_PENDING);
            }
        }
    }

    private enum ClipboardWriteOutcome {
        SUCCESS,
        FAILED_BEFORE_CHANGE,
        FAILED_AFTER_CHANGE
    }

    enum OwnershipState {
        OWNED,
        CHANGED,
        BUSY
    }

    enum RestoreOutcome {
        RESTORED,
        CHANGED,
        RESTORE_SCHEDULED,
        RESTORE_PENDING;

        boolean needsAttention() {
            return this == RESTORE_PENDING;
        }

        boolean isTerminal() {
            return this == RESTORED || this == CHANGED;
        }
    }

    private enum RestorePhase {
        TEMPORARY,
        FLUSHING,
        FINISHED
    }

    private record ClipboardPreparation(
        ClipboardLease lease,
        boolean fallbackAllowed,
        boolean restorePending,
        boolean cancelled
    ) {
        private static ClipboardPreparation ready(ClipboardLease lease) {
            return new ClipboardPreparation(lease, false, false, false);
        }

        private static ClipboardPreparation fallback() {
            return new ClipboardPreparation(null, true, false, false);
        }

        private static ClipboardPreparation stopWithPendingRestore() {
            return new ClipboardPreparation(null, false, true, false);
        }

        private static ClipboardPreparation cancelledPreparation() {
            return new ClipboardPreparation(null, false, false, true);
        }
    }

    private record TemporaryWrite(
        ClipboardWriteOutcome outcome,
        int sequence,
        boolean tokenInstalled
    ) {
        private static TemporaryWrite beforeChangeFailure() {
            return new TemporaryWrite(ClipboardWriteOutcome.FAILED_BEFORE_CHANGE, 0, false);
        }
    }

    /** Long-lived OLE STA. It owns the hidden clipboard hwnd and pumps Windows messages. */
    private final class StaWorker {
        private final BlockingQueue<StaTask<?>> tasks = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> initialized = new CompletableFuture<>();
        private final Thread thread;
        private volatile boolean running = true;
        private volatile boolean closing;
        private boolean oleReady;
        private WinDef.HWND ownerWindow;
        private ClipboardLease pendingLease;

        private StaWorker() {
            thread = new Thread(this::run, "ahakey-clipboard-sta");
            thread.setDaemon(true);
            thread.start();
        }

        private void run() {
            try {
                WinNT.HRESULT init = Ole32.INSTANCE.OleInitialize(Pointer.NULL);
                oleReady = COMUtils.SUCCEEDED(init);
                if (oleReady) {
                    ownerWindow = user32.CreateWindowEx(
                        0, "STATIC", "AhaKeyClipboardOwner", 0,
                        0, 0, 0, 0, null, null, null, null
                    );
                } else {
                    logger.error("KeyboardInjector - STA OleInitialize 失败: 0x{}",
                        Long.toHexString(init.longValue()));
                }
            } catch (RuntimeException e) {
                logger.error("KeyboardInjector - STA 初始化失败: {}", e.getMessage(), e);
            } finally {
                initialized.complete(null);
            }

            while (running) {
                pumpMessages();
                try {
                    StaTask<?> task = tasks.poll(10, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.execute();
                    }
                } catch (InterruptedException ignored) {
                    // Shutdown is queue-driven; interruption only wakes the message loop.
                }
                servicePendingOnce();
            }

            if (pendingLease != null) {
                pendingLease.makeRestoreDueNow();
                RestoreOutcome outcome = pendingLease.attemptRestore(SHUTDOWN_RESTORE_RETRY_MS);
                if (outcome.needsAttention()) {
                    pendingLease.abandonAtShutdown();
                }
                pendingLease = null;
            }
            pumpMessages();
            if (ownerWindow != null) {
                user32.DestroyWindow(ownerWindow);
                ownerWindow = null;
            }
            if (oleReady) {
                Ole32.INSTANCE.OleUninitialize();
                oleReady = false;
            }
        }

        private <T> T call(Callable<T> callable) throws InterruptedException, ExecutionException {
            initialized.get();
            if (Thread.currentThread() == thread) {
                try {
                    return callable.call();
                } catch (Exception e) {
                    throw new ExecutionException(e);
                }
            }
            if (closing) {
                throw new IllegalStateException("clipboard STA is closing");
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            tasks.offer(new StaTask<>(callable, result));
            boolean interrupted = false;
            try {
                // Once a native task is queued, returning FAILED while it still
                // runs would be misleading and could invite a duplicate retry.
                // Wait for the real terminal outcome, then restore interrupt state.
                while (true) {
                    try {
                        return result.get();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private boolean ensurePendingSettled() {
            if (pendingLease == null) {
                return true;
            }
            int waitMs = pendingLease.millisUntilRestoreDue();
            if (waitMs > 0 && !waitWithMessagePump(waitMs)) {
                return false;
            }
            RestoreOutcome outcome = pendingLease.attemptRestore(RESTORE_RETRY_MS);
            if (outcome.isTerminal()) {
                pendingLease = null;
                return true;
            }
            return false;
        }

        private void retainPending(ClipboardLease lease) {
            if (pendingLease == null || pendingLease == lease) {
                pendingLease = lease;
                return;
            }
            throw new IllegalStateException("more than one unresolved clipboard lease");
        }

        private void servicePendingOnce() {
            if (pendingLease == null) {
                return;
            }
            if (!pendingLease.restoreIsDue()) {
                return;
            }
            RestoreOutcome outcome = pendingLease.attemptRestore(0);
            if (outcome.isTerminal()) {
                logger.info("KeyboardInjector - 后台剪贴板恢复完成: {}", outcome);
                pendingLease = null;
            }
        }

        private boolean waitWithMessagePump(int millis) {
            long deadline = System.nanoTime() + Math.max(0, millis) * 1_000_000L;
            do {
                pumpMessages();
                long remainingMs = Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
                if (remainingMs == 0) {
                    return true;
                }
                try {
                    Thread.sleep(Math.min(10, remainingMs));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } while (System.nanoTime() < deadline);
            return true;
        }

        private void pumpMessages() {
            WinUser.MSG message = new WinUser.MSG();
            while (user32.PeekMessage(message, null, 0, 0, PM_REMOVE)) {
                user32.TranslateMessage(message);
                user32.DispatchMessage(message);
            }
        }

        private synchronized void shutdown() {
            if (!closing) {
                closing = true;
                tasks.offer(new StaTask<>(() -> {
                    running = false;
                    return null;
                }, new CompletableFuture<>()));
            }
            if (Thread.currentThread() == thread) {
                running = false;
                return;
            }
            boolean interrupted = false;
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class StaTask<T> {
        private final Callable<T> callable;
        private final CompletableFuture<T> result;

        private StaTask(Callable<T> callable, CompletableFuture<T> result) {
            this.callable = callable;
            this.result = result;
        }

        private void execute() {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        }
    }

    private interface ClipboardUser32 extends StdCallLibrary {
        ClipboardUser32 INSTANCE = Native.load("user32", ClipboardUser32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean OpenClipboard(WinDef.HWND owner);
        boolean CloseClipboard();
        boolean EmptyClipboard();
        Pointer SetClipboardData(int format, Pointer memory);
        Pointer GetClipboardData(int format);
        int GetClipboardSequenceNumber();
        int RegisterClipboardFormat(String formatName);
        WinDef.HWND GetClipboardOwner();
    }

    private interface ClipboardKernel32 extends StdCallLibrary {
        ClipboardKernel32 INSTANCE = Native.load("kernel32", ClipboardKernel32.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GlobalAlloc(int flags, BaseTSD.SIZE_T bytes);
        Pointer GlobalLock(Pointer memory);
        boolean GlobalUnlock(Pointer memory);
        Pointer GlobalFree(Pointer memory);
        BaseTSD.SIZE_T GlobalSize(Pointer memory);
    }

    private interface OleClipboard extends StdCallLibrary {
        OleClipboard INSTANCE = Native.load("ole32", OleClipboard.class, W32APIOptions.DEFAULT_OPTIONS);

        WinNT.HRESULT OleGetClipboard(PointerByReference dataObject);
        WinNT.HRESULT OleSetClipboard(Pointer dataObject);
        WinNT.HRESULT OleIsCurrentClipboard(Pointer dataObject);
        WinNT.HRESULT OleFlushClipboard();
    }
}
