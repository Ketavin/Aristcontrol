package com.example.ahakey.service;

import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioPart;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.model.VoicePreset;
import com.example.ahakey.protocol.AhaKeyProtocol;

import java.util.List;

/** Standalone protocol smoke test for custom-key synchronization. */
public final class DeviceSyncServiceSmokeTest {
    private DeviceSyncServiceSmokeTest() {
    }

    public static void main(String[] args) {
        require(ModeSlot.values().length == 3, "current firmware exposes exactly three modes");

        StudioState state = new StudioState();
        var macroKey = state.getKeyConfig(ModeSlot.MODE0, StudioPart.KEY1);
        macroKey.setVoicePreset(VoicePreset.CUSTOM);
        macroKey.getMacro().clear();
        macroKey.addMacroStep("UP_ALL_KEYS", 0);

        List<DeviceSyncService.LabeledCommand> current =
            DeviceSyncService.commandsForModes(state, ModeSlot.MODE0);
        require(current.size() == 13, "one mode should emit 4 x (clear, binding, description) + save");
        assertFrame(current.get(0).data(), 0x73, 0x73, 0, 0);
        assertFrame(current.get(1).data(), 0x73, 0x74, 0, 0, 0x04, 0x00);
        require(command(current.get(current.size() - 1).data()) == 0x04, "save command must be last");
        require(current.stream().noneMatch(item -> {
            int cmd = command(item.data());
            return cmd == 0x84 || cmd == 0x85;
        }), "current protocol save must not emit legacy 0x84/0x85 light commands");

        List<DeviceSyncService.LabeledCommand> legacy =
            DeviceSyncService.commandsForModes(state, true, ModeSlot.MODE0);
        require(legacy.stream().anyMatch(item -> command(item.data()) == 0x84),
            "legacy profile must retain per-mode light command");
        require(legacy.stream().anyMatch(item -> command(item.data()) == 0x85),
            "legacy profile must retain brightness command");

        macroKey.getMacro().clear();
        for (int i = 0; i < 50; i++) {
            macroKey.addMacroStep("DOWN_KEY", 0x04);
        }
        boolean rejected = false;
        try {
            DeviceSyncService.commandsForModes(state, ModeSlot.MODE0);
        } catch (IllegalArgumentException expected) {
            rejected = expected.getMessage().contains("49");
        }
        require(rejected, "macros larger than the firmware's 98-byte limit must be rejected");

        System.out.println("Device sync service smoke test passed");
    }

    private static int command(byte[] frame) {
        return frame[2] & 0xFF;
    }

    private static void assertFrame(byte[] actual, int command, int... payload) {
        require(actual.length == payload.length + 5, "unexpected frame length");
        require((actual[0] & 0xFF) == 0xAA && (actual[1] & 0xFF) == 0xBB, "missing frame header");
        require((actual[2] & 0xFF) == command, "unexpected command byte");
        for (int i = 0; i < payload.length; i++) {
            require((actual[i + 3] & 0xFF) == payload[i], "unexpected payload byte " + i);
        }
        require((actual[actual.length - 2] & 0xFF) == 0xCC
            && (actual[actual.length - 1] & 0xFF) == 0xDD, "missing frame trailer");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
