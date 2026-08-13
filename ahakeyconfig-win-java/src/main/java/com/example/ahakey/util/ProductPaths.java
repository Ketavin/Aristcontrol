package com.example.ahakey.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Product identity and one-way migration from the legacy AhaKeyStudio paths. */
public final class ProductPaths {
    public static final String DISPLAY_NAME = "Arist AI Control";
    public static final String APP_DIRECTORY_NAME = "AristAIControl";
    public static final String LEGACY_APP_DIRECTORY_NAME = "AhaKeyStudio-Qwen";

    private static final Logger logger = LoggerFactory.getLogger(ProductPaths.class);
    private static final String USER_DATA_DIRECTORY = ".arist-ai-control";
    private static final String LEGACY_USER_DATA_DIRECTORY = ".ahakey";

    private ProductPaths() {
    }

    public static Path draftPath() {
        return Path.of(System.getProperty("user.home"), USER_DATA_DIRECTORY, "studio-draft.json");
    }

    public static void migrateLegacyUserData() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path localAppDataPath = localAppData == null || localAppData.isBlank()
            ? null
            : Path.of(localAppData);
        try {
            migrateLegacyUserData(Path.of(System.getProperty("user.home")), localAppDataPath);
        } catch (IOException e) {
            logger.warn("Legacy Arist AI Control data migration was incomplete: {}", e.getMessage());
        }
    }

    public static void migrateLegacyDraft() {
        Path userHome = Path.of(System.getProperty("user.home"));
        try {
            copyIfMissing(
                userHome.resolve(LEGACY_USER_DATA_DIRECTORY).resolve("studio-draft.json"),
                userHome.resolve(USER_DATA_DIRECTORY).resolve("studio-draft.json")
            );
        } catch (IOException e) {
            logger.warn("Legacy configuration draft migration was incomplete: {}", e.getMessage());
        }
    }

    static void migrateLegacyUserData(Path userHome, Path localAppData) throws IOException {
        copyIfMissing(
            userHome.resolve(LEGACY_USER_DATA_DIRECTORY).resolve("studio-draft.json"),
            userHome.resolve(USER_DATA_DIRECTORY).resolve("studio-draft.json")
        );
        if (localAppData == null) {
            return;
        }

        Path legacyVoiceData = localAppData.resolve("AhaKeyVoiceBridge");
        Path productData = localAppData.resolve(APP_DIRECTORY_NAME);
        copyIfMissing(legacyVoiceData.resolve("dashscope.key"), productData.resolve("dashscope.key"));

        Path legacyStudioData = localAppData.resolve(LEGACY_APP_DIRECTORY_NAME);
        copyIfMissing(legacyStudioData.resolve("terminology.txt"), productData.resolve("terminology.txt"));
        copyIfMissing(legacyStudioData.resolve("corrections.tsv"), productData.resolve("corrections.tsv"));
    }

    private static void copyIfMissing(Path source, Path destination) throws IOException {
        if (!Files.isRegularFile(source) || Files.exists(destination)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        logger.info("Migrated legacy product data: {} -> {}", source, destination);
    }
}
