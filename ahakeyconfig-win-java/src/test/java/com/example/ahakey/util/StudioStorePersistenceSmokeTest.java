package com.example.ahakey.util;

import com.example.ahakey.model.KeyConfig;
import com.example.ahakey.model.MacroStep;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.model.VoicePreset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Standalone smoke test for complete StudioStore round trips and legacy-draft migration. */
public final class StudioStorePersistenceSmokeTest {

    private StudioStorePersistenceSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        String originalHome = System.getProperty("user.home");
        Path testHome = Files.createTempDirectory("arist-ai-control-store-smoke-");
        try {
            System.setProperty("user.home", testHome.toString());
            verifyCompleteRoundTrip();
            verifyMissingLegacyFieldsUseDefaults(testHome);
            System.out.println("Studio store persistence smoke test passed");
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
            deleteTree(testHome);
        }
    }

    private static void verifyCompleteRoundTrip() {
        StudioState.PersistedDraft saved = StudioState.PersistedDraft.defaults();
        saved.revision = 84;
        saved.ahaTypeEnabled = false;
        saved.lightBarPreviewId = "waitingApproval";
        saved.lightBrightness = 77;

        for (int mode = 0; mode < saved.modes.length; mode++) {
            StudioState.PersistedDraft.ModeDraft draft = saved.modes[mode];
            int base = 1_000 + mode * 100;
            draft.key1Hid = base + 1;
            draft.key1Desc = "Mode " + mode + " custom K1";
            draft.key1Macro = macro("DOWN_KEY", base + 11);
            draft.key2Hid = base + 2;
            draft.key2Desc = "Mode " + mode + " custom K2";
            draft.key2Macro = macro("UP_KEY", base + 12);
            draft.key3Hid = base + 3;
            draft.key3Desc = "Mode " + mode + " custom K3";
            draft.key3Macro = macro("DELAY", base + 13);
            draft.key4Hid = base + 4;
            draft.key4Desc = "Mode " + mode + " custom K4";
            draft.key4Macro = macro("DOWN_KEY", base + 14);
            draft.oledSummary = "OLED title " + mode;
            draft.oledCaption = "OLED subtitle " + mode;
            draft.oledGifPath = "C:/Arist AI Control Assets/mode-" + mode + ".gif";
            draft.oledFps = 6 + mode;
            draft.oledFrameCount = 20 + mode;
            draft.voicePresetId = VoicePreset.WECHAT.name();
            draft.aiLightEffectIds = new String[]{"off", "comet", "successSweep", "breathing"};
        }

        StudioStore.save(saved);
        StudioState.PersistedDraft loaded = StudioStore.loadOrDefault();

        require(loaded.revision == saved.revision, "revision was not preserved");
        require(loaded.ahaTypeEnabled == saved.ahaTypeEnabled, "AhaType preference was not preserved");
        require(saved.lightBarPreviewId.equals(loaded.lightBarPreviewId), "light preview was not preserved");
        require(loaded.lightBrightness == saved.lightBrightness, "light brightness was not preserved");
        require(loaded.modes.length == saved.modes.length, "mode count changed during round trip");
        for (int mode = 0; mode < saved.modes.length; mode++) {
            assertModeEquals(saved.modes[mode], loaded.modes[mode], "mode " + mode, true);
        }
    }

    private static void verifyMissingLegacyFieldsUseDefaults(Path testHome) throws Exception {
        Path draftPath = testHome.resolve(".arist-ai-control").resolve("studio-draft.json");
        String legacyJson = """
            {
              "revision": 7,
              "modes": [
                {
                  "key1Hid": 0,
                  "key1Desc": "Legacy custom K1",
                  "oledGifPath": "C:/legacy/old.gif",
                  "oledFrameCount": 0,
                  "voicePresetId": "WECHAT"
                }
              ]
            }
            """;
        Files.writeString(draftPath, legacyJson, StandardCharsets.UTF_8);

        StudioState.PersistedDraft defaults = StudioState.PersistedDraft.defaults();
        StudioState.PersistedDraft loaded = StudioStore.loadOrDefault();
        StudioState.PersistedDraft.ModeDraft expectedMode = defaults.modes[0];
        StudioState.PersistedDraft.ModeDraft actualMode = loaded.modes[0];

        require(loaded.revision == 7, "legacy revision was not preserved");
        require(loaded.ahaTypeEnabled == defaults.ahaTypeEnabled, "missing AhaType field did not use default");
        require(defaults.lightBarPreviewId.equals(loaded.lightBarPreviewId), "missing light preview did not use default");
        require(loaded.lightBrightness == defaults.lightBrightness, "missing light brightness did not use default");
        require(actualMode.key1Hid == 0, "explicit zero HID was mistaken for a missing field");
        require("Legacy custom K1".equals(actualMode.key1Desc), "legacy key description was not preserved");
        assertMacroEquals(expectedMode.key1Macro, actualMode.key1Macro, "missing K1 macro", false);
        require(actualMode.key2Hid == expectedMode.key2Hid, "missing K2 HID did not use default");
        require(expectedMode.key2Desc.equals(actualMode.key2Desc), "missing K2 description did not use default");
        assertMacroEquals(expectedMode.key2Macro, actualMode.key2Macro, "missing K2 macro", false);
        require(actualMode.key3Hid == expectedMode.key3Hid, "missing K3 HID did not use default");
        require(expectedMode.key3Desc.equals(actualMode.key3Desc), "missing K3 description did not use default");
        assertMacroEquals(expectedMode.key3Macro, actualMode.key3Macro, "missing K3 macro", false);
        require(actualMode.key4Hid == expectedMode.key4Hid, "missing K4 HID did not use default");
        require(expectedMode.key4Desc.equals(actualMode.key4Desc), "missing K4 description did not use default");
        assertMacroEquals(expectedMode.key4Macro, actualMode.key4Macro, "missing K4 macro", false);
        require(expectedMode.oledSummary.equals(actualMode.oledSummary), "missing OLED title did not use default");
        require(expectedMode.oledCaption.equals(actualMode.oledCaption), "missing OLED subtitle did not use default");
        require("C:/legacy/old.gif".equals(actualMode.oledGifPath), "legacy OLED path was not preserved");
        require(actualMode.oledFps == expectedMode.oledFps, "missing OLED FPS did not use default");
        require(actualMode.oledFrameCount == 0, "explicit zero OLED frame count was not preserved");
        require(VoicePreset.WECHAT.name().equals(actualMode.voicePresetId), "legacy voice preset was not preserved");
        require(
            Arrays.equals(expectedMode.aiLightEffectIds, actualMode.aiLightEffectIds),
            "missing per-state light effects did not use defaults"
        );

        for (int mode = 1; mode < defaults.modes.length; mode++) {
            assertModeEquals(defaults.modes[mode], loaded.modes[mode], "missing legacy mode " + mode, false);
        }
    }

    private static List<MacroStep> macro(String action, int param) {
        KeyConfig key = new KeyConfig();
        key.addMacroStep(action, param);
        return key.getMacro();
    }

    private static void assertModeEquals(
        StudioState.PersistedDraft.ModeDraft expected,
        StudioState.PersistedDraft.ModeDraft actual,
        String label,
        boolean requireSameMacroIds
    ) {
        require(expected.key1Hid == actual.key1Hid, label + " K1 HID changed");
        require(Objects.equals(expected.key1Desc, actual.key1Desc), label + " K1 description changed");
        assertMacroEquals(expected.key1Macro, actual.key1Macro, label + " K1 macro", requireSameMacroIds);
        require(expected.key2Hid == actual.key2Hid, label + " K2 HID changed");
        require(Objects.equals(expected.key2Desc, actual.key2Desc), label + " K2 description changed");
        assertMacroEquals(expected.key2Macro, actual.key2Macro, label + " K2 macro", requireSameMacroIds);
        require(expected.key3Hid == actual.key3Hid, label + " K3 HID changed");
        require(Objects.equals(expected.key3Desc, actual.key3Desc), label + " K3 description changed");
        assertMacroEquals(expected.key3Macro, actual.key3Macro, label + " K3 macro", requireSameMacroIds);
        require(expected.key4Hid == actual.key4Hid, label + " K4 HID changed");
        require(Objects.equals(expected.key4Desc, actual.key4Desc), label + " K4 description changed");
        assertMacroEquals(expected.key4Macro, actual.key4Macro, label + " K4 macro", requireSameMacroIds);
        require(Objects.equals(expected.oledSummary, actual.oledSummary), label + " OLED title changed");
        require(Objects.equals(expected.oledCaption, actual.oledCaption), label + " OLED subtitle changed");
        require(Objects.equals(expected.oledGifPath, actual.oledGifPath), label + " OLED path changed");
        require(expected.oledFps == actual.oledFps, label + " OLED FPS changed");
        require(expected.oledFrameCount == actual.oledFrameCount, label + " OLED frame count changed");
        require(Objects.equals(expected.voicePresetId, actual.voicePresetId), label + " voice preset changed");
        require(Arrays.equals(expected.aiLightEffectIds, actual.aiLightEffectIds), label + " light effects changed");
    }

    private static void assertMacroEquals(
        List<MacroStep> expected,
        List<MacroStep> actual,
        String label,
        boolean requireSameIds
    ) {
        require(expected != null, label + " expected macro is null");
        require(actual != null, label + " actual macro is null");
        require(expected.size() == actual.size(), label + " step count changed");

        KeyConfig expectedKey = new KeyConfig();
        expectedKey.setMacro(expected);
        KeyConfig actualKey = new KeyConfig();
        actualKey.setMacro(actual);
        for (int i = 0; i < expected.size(); i++) {
            MacroStep expectedStep = expected.get(i);
            MacroStep actualStep = actual.get(i);
            if (requireSameIds) {
                require(expectedStep.getId().equals(actualStep.getId()), label + " step ID changed at " + i);
            }
            require(expectedKey.getMacroStepAction(i).equals(actualKey.getMacroStepAction(i)), label + " action changed at " + i);
            require(expectedStep.getParam() == actualStep.getParam(), label + " parameter changed at " + i);
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
