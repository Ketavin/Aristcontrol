package com.example.ahakey.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 模型配置管理器
 * 支持从配置文件动态加载模型路径和参数
 * 用户只需替换模型文件或修改配置文件即可切换模型，无需修改代码
 */
public class ModelConfig {

    private static final Logger logger = LoggerFactory.getLogger(ModelConfig.class);

    private static ModelConfig instance;
    private Properties properties;

    // 配置键名常量
    private static final String MODEL_ENABLED = "model.enabled";
    private static final String MODEL_PATH = "model.path";
    private static final String TOKENS_PATH = "tokens.path";
    private static final String MODEL_TYPE = "model.type";
    private static final String NUM_THREADS = "num_threads";
    private static final String SAMPLE_RATE = "sample_rate";
    private static final String STATUS_POLL_PERIOD = "status.poll.period.seconds";
    private static final String VAD_ENABLED = "vad.enabled";
    private static final String VAD_MODEL_PATH = "vad.model.path";
    private static final String ASR_PROVIDER = "asr.provider";
    private static final String QWEN_API_BASE = "qwen.api.base";
    private static final String QWEN_MODEL = "qwen.model";
    private static final String QWEN_KEY_PATH = "qwen.key.path";
    private static final String QWEN_GLOSSARY = "qwen.glossary";
    private static final String QWEN_ENABLE_ITN = "qwen.enable_itn";
    private static final String QWEN_TERMINOLOGY_PATH = "qwen.terminology.path";
    private static final String QWEN_CORRECTIONS_PATH = "qwen.corrections.path";
    private static final String QWEN_CORRECTION_MIN_CONFIDENCE = "qwen.correction.min_confidence";
    private static final String QWEN_POLISH_API_BASE = "qwen.polish.api.base";
    private static final String QWEN_POLISH_MODEL = "qwen.polish.model";
    private static final String QWEN_POLISH_TIMEOUT_SECONDS = "qwen.polish.timeout.seconds";
    private static final String QWEN_POLISH_TEMPERATURE = "qwen.polish.temperature";
    private static final String QWEN_POLISH_MAX_OUTPUT_TOKENS = "qwen.polish.max.output.tokens";
    private static final String VOICE_AUTO_START = "voice.auto_start";

    // 默认配置值
    private static final boolean DEFAULT_MODEL_ENABLED = false;
    private static final String DEFAULT_MODEL_PATH = "models";
    private static final String DEFAULT_TOKENS_PATH = "tokens.txt";
    private static final String DEFAULT_MODEL_TYPE = "SENSE_VOICE";
    private static final int DEFAULT_NUM_THREADS = 4;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int DEFAULT_STATUS_POLL_PERIOD = 3;
    private static final boolean DEFAULT_VAD_ENABLED = false;
    private static final String DEFAULT_VAD_MODEL_PATH = "models/silero_vad.onnx";
    private static final String DEFAULT_ASR_PROVIDER = "local";
    private static final String DEFAULT_QWEN_API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_QWEN_MODEL = "qwen3-asr-flash-2026-02-10";
    private static final String DEFAULT_QWEN_KEY_PATH = "AristAIControl/dashscope.key";
    private static final String DEFAULT_QWEN_GLOSSARY = "AhaKey,Arist,Arist.ai,Codex,Claude,Cursor,ChatGPT,Gemini,OpenAI,GPT,API,MCP,PowerShell,GitHub";
    private static final String DEFAULT_QWEN_TERMINOLOGY_PATH = "AristAIControl/terminology.txt";
    private static final String DEFAULT_QWEN_CORRECTIONS_PATH = "AristAIControl/corrections.tsv";
    private static final double DEFAULT_QWEN_CORRECTION_MIN_CONFIDENCE = 0.98;
    private static final String DEFAULT_QWEN_POLISH_API_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_QWEN_POLISH_MODEL = "qwen-flash";
    private static final int DEFAULT_QWEN_POLISH_TIMEOUT_SECONDS = 8;
    private static final double DEFAULT_QWEN_POLISH_TEMPERATURE = 0.1;
    private static final int DEFAULT_QWEN_POLISH_MAX_OUTPUT_TOKENS = 512;

    private ModelConfig() {
        loadConfig();
    }

    /**
     * 获取单例实例
     */
    public static synchronized ModelConfig getInstance() {
        if (instance == null) {
            instance = new ModelConfig();
        }
        return instance;
    }

    /**
     * 重新加载配置文件
     */
    public void reload() {
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        properties = new Properties();

        // 尝试从类路径加载配置文件
        try (InputStream is = getClass().getResourceAsStream("/model_config.properties")) {
            if (is != null) {
                properties.load(is);
                logger.info("模型配置文件加载成功");
            } else {
                logger.warn("未找到模型配置文件，使用默认配置");
            }
        } catch (IOException e) {
            logger.error("加载模型配置文件失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取模型启用状态
     * @return true 表示启用本地模型和语音输入功能，false 表示禁用
     */
    public boolean isEnabled() {
        return getBooleanProperty(MODEL_ENABLED, DEFAULT_MODEL_ENABLED);
    }

    /**
     * 获取模型文件路径
     */
    public String getModelPath() {
        return properties.getProperty(MODEL_PATH, DEFAULT_MODEL_PATH);
    }

    /**
     * 获取词汇表文件路径
     */
    public String getTokensPath() {
        return properties.getProperty(TOKENS_PATH, DEFAULT_TOKENS_PATH);
    }

    /**
     * 获取模型类型
     */
    public String getModelType() {
        return properties.getProperty(MODEL_TYPE, DEFAULT_MODEL_TYPE);
    }

    /**
     * 获取线程数
     */
    public int getNumThreads() {
        return getIntProperty(NUM_THREADS, DEFAULT_NUM_THREADS);
    }

    /**
     * 获取采样率
     */
    public int getSampleRate() {
        return getIntProperty(SAMPLE_RATE, DEFAULT_SAMPLE_RATE);
    }

    /**
     * 获取设备状态轮询周期（秒）
     */
    public int getStatusPollPeriodSeconds() {
        return getIntProperty(STATUS_POLL_PERIOD, DEFAULT_STATUS_POLL_PERIOD);
    }

    /**
     * 获取 VAD 启用状态
     */
    public boolean isVadEnabled() {
        return getBooleanProperty(VAD_ENABLED, DEFAULT_VAD_ENABLED);
    }

    /**
     * 获取 VAD 模型路径
     */
    public String getVadModelPath() {
        return properties.getProperty(VAD_MODEL_PATH, DEFAULT_VAD_MODEL_PATH);
    }

    public String getAsrProvider() {
        return getStringProperty(ASR_PROVIDER, DEFAULT_ASR_PROVIDER);
    }

    /**
     * Whether the configured speech provider can be initialized.
     * model.enabled only controls providers that need the bundled local model;
     * cloud providers such as Qwen must remain available without that payload.
     */
    public boolean isSpeechRecognitionEnabled() {
        String provider = getAsrProvider().trim().toLowerCase(java.util.Locale.ROOT);
        return switch (provider) {
            case "local", "sherpa", "paraformer" -> isEnabled();
            default -> true;
        };
    }

    public String getQwenApiBase() {
        return getStringProperty(QWEN_API_BASE, DEFAULT_QWEN_API_BASE);
    }

    public String getQwenModel() {
        return getStringProperty(QWEN_MODEL, DEFAULT_QWEN_MODEL);
    }

    public String getQwenKeyPath() {
        return getStringProperty(QWEN_KEY_PATH, DEFAULT_QWEN_KEY_PATH);
    }

    public String getQwenGlossary() {
        return getStringProperty(QWEN_GLOSSARY, DEFAULT_QWEN_GLOSSARY);
    }

    public String getQwenTerminologyPath() {
        return getStringProperty(QWEN_TERMINOLOGY_PATH, DEFAULT_QWEN_TERMINOLOGY_PATH);
    }

    public String getQwenCorrectionsPath() {
        return getStringProperty(QWEN_CORRECTIONS_PATH, DEFAULT_QWEN_CORRECTIONS_PATH);
    }

    public double getQwenCorrectionMinConfidence() {
        return getDoubleProperty(QWEN_CORRECTION_MIN_CONFIDENCE, DEFAULT_QWEN_CORRECTION_MIN_CONFIDENCE);
    }

    public String getQwenPolishApiBase() {
        return getStringProperty(QWEN_POLISH_API_BASE, DEFAULT_QWEN_POLISH_API_BASE);
    }

    public String getQwenPolishModel() {
        return getStringProperty(QWEN_POLISH_MODEL, DEFAULT_QWEN_POLISH_MODEL);
    }

    public int getQwenPolishTimeoutSeconds() {
        return Math.max(1, getIntProperty(QWEN_POLISH_TIMEOUT_SECONDS, DEFAULT_QWEN_POLISH_TIMEOUT_SECONDS));
    }

    public double getQwenPolishTemperature() {
        return getDoubleProperty(QWEN_POLISH_TEMPERATURE, DEFAULT_QWEN_POLISH_TEMPERATURE);
    }

    public int getQwenPolishMaxOutputTokens() {
        return Math.max(64, getIntProperty(QWEN_POLISH_MAX_OUTPUT_TOKENS, DEFAULT_QWEN_POLISH_MAX_OUTPUT_TOKENS));
    }

    public boolean isQwenItnEnabled() {
        return getBooleanProperty(QWEN_ENABLE_ITN, true);
    }

    public boolean isVoiceAutoStartEnabled() {
        return getBooleanProperty(VOICE_AUTO_START, true);
    }

    private String getStringProperty(String key, String defaultValue) {
        String systemOverride = System.getProperty(key);
        if (systemOverride != null && !systemOverride.isBlank()) {
            return systemOverride.trim();
        }
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 安全获取布尔属性
     */
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value != null && !value.isEmpty()) {
            return Boolean.parseBoolean(value.trim());
        }
        return defaultValue;
    }

    /**
     * 安全获取整数属性
     */
    private int getIntProperty(String key, int defaultValue) {
        try {
            String value = properties.getProperty(key);
            if (value != null && !value.isEmpty()) {
                return Integer.parseInt(value.trim());
            }
        } catch (NumberFormatException e) {
            logger.warn("配置项 {} 值无效，使用默认值: {}", key, defaultValue);
        }
        return defaultValue;
    }

    private double getDoubleProperty(String key, double defaultValue) {
        try {
            String value = properties.getProperty(key);
            if (value != null && !value.isEmpty()) {
                return Double.parseDouble(value.trim());
            }
        } catch (NumberFormatException e) {
            logger.warn("配置项 {} 值无效，使用默认值: {}", key, defaultValue);
        }
        return defaultValue;
    }

    /**
     * 打印当前配置信息
     */
    public void printConfig() {
        logger.debug("========== 模型配置 ==========");
        logger.debug("模型启用: {}", isEnabled());
        logger.debug("模型路径: {}", getModelPath());
        logger.debug("词汇表路径: {}", getTokensPath());
        logger.debug("模型类型: {}", getModelType());
        logger.debug("线程数: {}", getNumThreads());
        logger.debug("采样率: {}", getSampleRate());
        logger.debug("VAD 启用: {}", isVadEnabled());
        logger.debug("VAD 模型路径: {}", getVadModelPath());
        logger.debug("ASR provider: {}", getAsrProvider());
        if ("qwen".equalsIgnoreCase(getAsrProvider())) {
            logger.debug("Qwen 模型: {}", getQwenModel());
            logger.debug("Qwen API: {}", getQwenApiBase());
        }
        logger.debug("==============================");
    }
}
