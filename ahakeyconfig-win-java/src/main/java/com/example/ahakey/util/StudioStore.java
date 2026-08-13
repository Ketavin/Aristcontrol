package com.example.ahakey.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.model.StudioState;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists the Arist AI Control configuration draft. */
public final class StudioStore {

    private static final Logger logger = LoggerFactory.getLogger(StudioStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path DRAFT_PATH = ProductPaths.draftPath();

    private StudioStore() {
    }

    public static void save(StudioState.PersistedDraft draft) {
        ProductPaths.migrateLegacyDraft();
        try {
            Files.createDirectories(DRAFT_PATH.getParent());
            MAPPER.writeValue(DRAFT_PATH.toFile(), draft);
        } catch (IOException e) {
            logger.error("StudioStore.save failed: {}", e.getMessage(), e);
        }
    }

    public static StudioState.PersistedDraft loadOrDefault() {
        ProductPaths.migrateLegacyDraft();
        File file = DRAFT_PATH.toFile();
        if (!file.exists()) {
            return StudioState.PersistedDraft.defaults();
        }
        try {
            JsonNode root = MAPPER.readTree(file);
            StudioState.PersistedDraft defaults = StudioState.PersistedDraft.defaults();
            if (root == null || !root.isObject()) {
                return defaults;
            }

            StudioState.PersistedDraft savedDraft = MAPPER.treeToValue(root, StudioState.PersistedDraft.class);
            mergeGlobalFields(root, savedDraft, defaults);
            mergeModeFields(root.get("modes"), savedDraft.modes, defaults.modes);
            return defaults;
        } catch (IOException e) {
            return StudioState.PersistedDraft.defaults();
        }
    }

    private static void mergeGlobalFields(
        JsonNode root,
        StudioState.PersistedDraft saved,
        StudioState.PersistedDraft target
    ) {
        if (hasValue(root, "revision")) {
            target.revision = saved.revision;
        }
        if (hasValue(root, "ahaTypeEnabled")) {
            target.ahaTypeEnabled = saved.ahaTypeEnabled;
        }
        if (hasValue(root, "lightBarPreviewId")) {
            target.lightBarPreviewId = saved.lightBarPreviewId;
        }
        if (hasValue(root, "lightBrightness")) {
            target.lightBrightness = saved.lightBrightness;
        }
    }

    private static void mergeModeFields(
        JsonNode savedModeNodes,
        StudioState.PersistedDraft.ModeDraft[] savedModes,
        StudioState.PersistedDraft.ModeDraft[] targetModes
    ) {
        if (savedModeNodes == null || !savedModeNodes.isArray() || savedModes == null || targetModes == null) {
            return;
        }

        int count = Math.min(savedModeNodes.size(), Math.min(savedModes.length, targetModes.length));
        for (int i = 0; i < count; i++) {
            JsonNode modeNode = savedModeNodes.get(i);
            StudioState.PersistedDraft.ModeDraft saved = savedModes[i];
            StudioState.PersistedDraft.ModeDraft target = targetModes[i];
            if (modeNode == null || !modeNode.isObject() || saved == null || target == null) {
                continue;
            }

            if (hasValue(modeNode, "key1Hid")) target.key1Hid = saved.key1Hid;
            if (hasValue(modeNode, "key1Desc")) target.key1Desc = saved.key1Desc;
            if (hasValue(modeNode, "key1Macro")) target.key1Macro = saved.key1Macro;
            if (hasValue(modeNode, "key2Hid")) target.key2Hid = saved.key2Hid;
            if (hasValue(modeNode, "key2Desc")) target.key2Desc = saved.key2Desc;
            if (hasValue(modeNode, "key2Macro")) target.key2Macro = saved.key2Macro;
            if (hasValue(modeNode, "key3Hid")) target.key3Hid = saved.key3Hid;
            if (hasValue(modeNode, "key3Desc")) target.key3Desc = saved.key3Desc;
            if (hasValue(modeNode, "key3Macro")) target.key3Macro = saved.key3Macro;
            if (hasValue(modeNode, "key4Hid")) target.key4Hid = saved.key4Hid;
            if (hasValue(modeNode, "key4Desc")) target.key4Desc = saved.key4Desc;
            if (hasValue(modeNode, "key4Macro")) target.key4Macro = saved.key4Macro;
            if (hasValue(modeNode, "oledSummary")) target.oledSummary = saved.oledSummary;
            if (hasValue(modeNode, "oledCaption")) target.oledCaption = saved.oledCaption;
            if (hasValue(modeNode, "oledGifPath")) target.oledGifPath = saved.oledGifPath;
            if (hasValue(modeNode, "oledFps")) target.oledFps = saved.oledFps;
            if (hasValue(modeNode, "oledFrameCount")) target.oledFrameCount = saved.oledFrameCount;
            if (hasValue(modeNode, "voicePresetId")) target.voicePresetId = saved.voicePresetId;
            if (hasValue(modeNode, "aiLightEffectIds")) target.aiLightEffectIds = saved.aiLightEffectIds;
        }
    }

    private static boolean hasValue(JsonNode node, String fieldName) {
        return node.has(fieldName) && !node.get(fieldName).isNull();
    }
}
