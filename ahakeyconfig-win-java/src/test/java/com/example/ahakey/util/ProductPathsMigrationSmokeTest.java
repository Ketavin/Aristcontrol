package com.example.ahakey.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Verifies that the Arist rebrand preserves every legacy per-user file. */
public final class ProductPathsMigrationSmokeTest {
    private ProductPathsMigrationSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("arist-ai-control-migration-");
        Path userHome = root.resolve("home");
        Path localAppData = root.resolve("local");
        try {
            write(userHome.resolve(".ahakey/studio-draft.json"), "legacy-draft");
            write(localAppData.resolve("AhaKeyVoiceBridge/dashscope.key"), "legacy-key");
            write(localAppData.resolve("AhaKeyStudio-Qwen/terminology.txt"), "legacy-terms");
            write(localAppData.resolve("AhaKeyStudio-Qwen/corrections.tsv"), "legacy-corrections");

            ProductPaths.migrateLegacyUserData(userHome, localAppData);

            require(read(userHome.resolve(".arist-ai-control/studio-draft.json")).equals("legacy-draft"),
                "studio draft was not migrated");
            require(read(localAppData.resolve("AristAIControl/dashscope.key")).equals("legacy-key"),
                "DPAPI key blob was not migrated");
            require(read(localAppData.resolve("AristAIControl/terminology.txt")).equals("legacy-terms"),
                "terminology file was not migrated");
            require(read(localAppData.resolve("AristAIControl/corrections.tsv")).equals("legacy-corrections"),
                "corrections file was not migrated");

            write(localAppData.resolve("AristAIControl/terminology.txt"), "new-terms");
            ProductPaths.migrateLegacyUserData(userHome, localAppData);
            require(read(localAppData.resolve("AristAIControl/terminology.txt")).equals("new-terms"),
                "migration must not overwrite new product data");

            System.out.println("Arist AI Control legacy-data migration smoke test passed");
        } finally {
            deleteRecursively(root);
        }
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
