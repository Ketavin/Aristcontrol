package com.example.ahakey.service;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated GUI happy-path probe for a real editable Windows window. */
public final class KeyboardInjectorSwingIntegrationProbe {

    private static final String EXPECTED = "甲，乙。ChatGPT，Codex。AhaKey 507C 😀";
    private static final String WINDOW_TITLE = "AhaKey isolated injection probe 507C";

    private KeyboardInjectorSwingIntegrationProbe() {
    }

    public static void main(String[] args) throws Exception {
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        AtomicReference<JTextArea> textAreaRef = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame(WINDOW_TITLE);
            JTextArea textArea = new JTextArea();
            textArea.setLineWrap(true);
            frame.setLayout(new BorderLayout());
            frame.add(new JScrollPane(textArea), BorderLayout.CENTER);
            frame.setPreferredSize(new Dimension(640, 220));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            frame.setVisible(true);
            frame.toFront();
            textArea.requestFocusInWindow();
            frameRef.set(frame);
            textAreaRef.set(textArea);
        });

        int exitCode = 0;
        try {
            KeyboardInjector injector = new KeyboardInjector();
            KeyboardInjector.InjectionResult result;
            try {
                Thread.sleep(400);
                WinDef.HWND probeWindow = User32.INSTANCE.FindWindow(null, WINDOW_TITLE);
                if (probeWindow == null) {
                    throw new AssertionError("probe window hwnd not found");
                }
                forceForegroundForIsolatedProbe(probeWindow);
                SwingUtilities.invokeAndWait(() -> textAreaRef.get().requestFocusInWindow());
                Thread.sleep(250);
                KeyboardInjector.TargetSnapshot target = injector.captureTargetSnapshot();
                if (!WINDOW_TITLE.equals(target.title())) {
                    throw new AssertionError("probe window did not become foreground; refusing injection");
                }
                result = injector.injectText(EXPECTED, target, nativeCommit -> {
                    nativeCommit.run();
                    return true;
                });
                Thread.sleep(2_200);
            } finally {
                injector.release();
            }

            AtomicReference<String> actualRef = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> actualRef.set(textAreaRef.get().getText()));
            String actual = actualRef.get();
            System.out.println("INJECTION_RESULT=" + result);
            System.out.println("EXPECTED_TEXT=" + EXPECTED);
            System.out.println("ACTUAL_TEXT=" + actual);
            if (!EXPECTED.equals(actual)) {
                throw new AssertionError("GUI injection mismatch");
            }
            System.out.println("Keyboard injector Swing integration probe passed");
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            SwingUtilities.invokeAndWait(() -> frameRef.get().dispose());
        }
        System.exit(exitCode);
    }

    private static void forceForegroundForIsolatedProbe(WinDef.HWND probeWindow) {
        User32 windows = User32.INSTANCE;
        WinDef.HWND previous = windows.GetForegroundWindow();
        int previousThread = previous == null ? 0 : windows.GetWindowThreadProcessId(previous, null);
        int probeThread = windows.GetWindowThreadProcessId(probeWindow, null);
        boolean attached = previousThread != 0
            && probeThread != 0
            && previousThread != probeThread
            && windows.AttachThreadInput(
                new WinDef.DWORD(probeThread), new WinDef.DWORD(previousThread), true
            );
        try {
            windows.ShowWindow(probeWindow, 9);
            windows.BringWindowToTop(probeWindow);
            windows.SetForegroundWindow(probeWindow);
        } finally {
            if (attached) {
                windows.AttachThreadInput(
                    new WinDef.DWORD(probeThread), new WinDef.DWORD(previousThread), false
                );
            }
        }
    }
}
