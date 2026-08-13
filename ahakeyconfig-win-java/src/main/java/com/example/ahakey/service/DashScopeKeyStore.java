package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import com.sun.jna.platform.win32.Crypt32Util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;

/** Reads the existing per-user, DPAPI-encrypted DashScope key. */
public final class DashScopeKeyStore {

    private final ModelConfig config;

    public DashScopeKeyStore(ModelConfig config) {
        this.config = config;
    }

    public char[] readApiKey() throws IOException {
        Path keyPath = resolveKeyPath();
        if (!Files.isRegularFile(keyPath)) {
            throw new IOException("未找到百炼 API Key: " + keyPath);
        }

        byte[] encrypted;
        try {
            encrypted = Base64.getDecoder().decode(Files.readString(keyPath, StandardCharsets.UTF_8).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("百炼 API Key 文件格式无效", e);
        }

        byte[] clear = null;
        try {
            clear = Crypt32Util.cryptUnprotectData(encrypted);
            char[] key = new String(clear, StandardCharsets.UTF_8).trim().toCharArray();
            if (key.length < 4) {
                Arrays.fill(key, '\0');
                throw new IOException("百炼 API Key 为空或格式无效");
            }
            return key;
        } catch (RuntimeException e) {
            throw new IOException("无法用当前 Windows 用户解密百炼 API Key", e);
        } finally {
            if (clear != null) {
                Arrays.fill(clear, (byte) 0);
            }
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    Path resolveKeyPath() {
        Path configured = Path.of(config.getQwenKeyPath());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("Windows LOCALAPPDATA 不可用");
        }
        return Path.of(localAppData).resolve(configured).normalize();
    }
}
