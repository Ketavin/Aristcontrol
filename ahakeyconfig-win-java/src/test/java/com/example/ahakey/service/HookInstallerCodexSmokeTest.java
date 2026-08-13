package com.example.ahakey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Standalone, no-network smoke test for generated Codex approval hooks. */
public final class HookInstallerCodexSmokeTest {

    private static final int MANUAL_DIALOG_TIMEOUT_SECONDS = 15;

    private HookInstallerCodexSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path tempHome = Files.createTempDirectory("ahakey-codex-hook-test-");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempHome.toString());
            new HookInstaller(9000, message -> { }).install("Codex");

            Path hooksDir = tempHome.resolve(".ahakey").resolve("hooks");
            String core = Files.readString(hooksDir.resolve("ahakey-core.ps1"), StandardCharsets.UTF_8);
            String codex = Files.readString(hooksDir.resolve("ahakey-codex.ps1"), StandardCharsets.UTF_8);
            JsonNode hooks = new ObjectMapper().readTree(tempHome.resolve(".codex").resolve("hooks.json").toFile());

            require(core.contains("[Console]::In.ReadToEnd()"), "core hook did not capture Codex stdin");
            require(codex.contains("\"behavior\":\"allow\""), "Codex hook did not emit explicit allow");
            require(codex.contains("AhaKey is unavailable; approval denied safely."),
                "Codex hook did not fail closed when AhaKey is unavailable");
            require(codex.contains("Denied in AhaKey manual approval mode."),
                "Codex hook did not emit explicit manual deny");
            require(codex.contains("Popup($message, " + MANUAL_DIALOG_TIMEOUT_SECONDS + ","),
                "manual approval dialog did not have the expected finite timeout");
            require(codex.contains("'AhaKey Manual Approval', 4388)"),
                "manual approval dialog did not default safely to No");

            int hookTimeout = hooks.path("hooks").path("PermissionRequest").path(0)
                .path("hooks").path(0).path("timeout").asInt();
            require(hookTimeout > MANUAL_DIALOG_TIMEOUT_SECONDS,
                "PermissionRequest hook timeout must exceed the manual dialog timeout");

            System.out.println("Hook installer Codex smoke test passed");
        } finally {
            System.setProperty("user.home", originalHome);
            try (java.util.stream.Stream<Path> paths = Files.walk(tempHome)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // Best-effort cleanup for a disposable test directory.
                    }
                });
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
