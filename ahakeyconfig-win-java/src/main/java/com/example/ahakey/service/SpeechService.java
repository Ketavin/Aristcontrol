package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import com.k2fsa.sherpa.onnx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.*;

/**
 * 语音识别服务
 * 使用 Sherpa-ONNX 运行本地语音识别模型
 * 支持流式 Paraformer、SenseVoice、Whisper 等多种模型
 * 支持 VAD（语音活动检测）
 */
public class SpeechService implements SpeechRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(SpeechService.class);

    // 流式识别器（用于 Paraformer 等流式模型）
    private OnlineRecognizer onlineRecognizer;
    private OnlineStream onlineStream;

    // 离线识别器（用于 SenseVoice、Whisper 等非流式模型）
    private OfflineRecognizer offlineRecognizer;

    // VAD（语音活动检测）
    private Vad vad;

    // 标点符号模型
    private OfflinePunctuation punctuation;

    private Thread recognitionThread;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private Consumer<String> partialCallback;
    private Consumer<String> finalCallback;
    private Consumer<String> errorCallback;

    private ModelConfig config;
    private int sampleRate = 16000;

    private ExecutorService executorService;

    // 是否使用流式识别
    private boolean useOnlineRecognition = false;

    /**
     * 初始化语音识别服务
     */
    public void initialize() throws Exception {
        config = ModelConfig.getInstance();
        config.printConfig();

        loadNativeLibrary();

        String modelType = config.getModelType();
        useOnlineRecognition = modelType.contains("STREAMING") || modelType.contains("ONLINE");

        if (useOnlineRecognition) {
            initOnlineRecognizer();
        } else {
            initOfflineRecognizer();
        }

        // 初始化 VAD（如果配置了）
        if (config.isVadEnabled()) {
            initVad();
        }

        // 初始化标点符号模型
        initPunctuation();
    }

    /**
     * 初始化语音识别服务（兼容旧API）
     */
    public void initialize(String modelPath, String tokensPath) throws Exception {
        initialize();
    }

    /**
     * 加载 JNI 原生库
     * LibraryLoader 会自动从资源路径 sherpa-onnx/native/win-x64 加载
     */
    private void loadNativeLibrary() {
        try {
            com.example.ahakey.sherpa.LibraryLoader.load();
            logger.info("Sherpa-ONNX JNI 库加载成功");
        } catch (Exception e) {
            throw new RuntimeException("加载 JNI 库失败，请确保 sherpa-onnx-jni.dll 在 sherpa-onnx/native/win-x64 目录下", e);
        }
    }

    /**
     * 初始化流式识别器（用于 Paraformer 等流式模型）
     */
    private void initOnlineRecognizer() {
        String modelType = config.getModelType();

        try {
            // 获取模型目录路径（支持类路径和外部路径）
            String modelDirPath = getModelDirPath();
            logger.info("模型目录: {}", modelDirPath);

            String tokensPath = resolveModelFile("tokens.txt", modelDirPath);
            logger.info("tokens: {}", tokensPath);
            String encoderPath = resolveModelFile("encoder.int8.onnx", modelDirPath);
            logger.info("encoder: {}", encoderPath);
            String decoderPath = resolveModelFile("decoder.int8.onnx", modelDirPath);
            logger.info("decoder: {}", decoderPath);

            OnlineModelConfig modelConfig = null;

            if (modelType.contains("PARAFORMER")) {
                OnlineParaformerModelConfig paraformerConfig =
                    OnlineParaformerModelConfig.builder()
                        .setEncoder(encoderPath)
                        .setDecoder(decoderPath)
                        .build();

                modelConfig = OnlineModelConfig.builder()
                    .setParaformer(paraformerConfig)
                    .setTokens(tokensPath)
                    .setNumThreads(config.getNumThreads())
                    .setDebug(false)
                    .build();
                logger.info("Paraformer 模型配置完成");
            }

            if (modelConfig == null) {
                throw new RuntimeException("不支持的流式模型类型: " + modelType);
            }

            // 配置端点检测（用于自动断句）
            EndpointConfig endpointConfig = EndpointConfig.builder()
                .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(2.4f).setMinUtteranceLength(0).build())
                .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(1.2f).setMinUtteranceLength(0).build())
                .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(0.0f).setMinUtteranceLength(20.0f).build())
                .build();

            OnlineRecognizerConfig recognizerConfig =
                OnlineRecognizerConfig.builder()
                    .setOnlineModelConfig(modelConfig)
                    .setEndpointConfig(endpointConfig)
                    .setEnableEndpoint(true)
                    .build();

            logger.info("开始创建 OnlineRecognizer...");
            try {
                onlineRecognizer = new OnlineRecognizer(recognizerConfig);
                logger.info("流式 {} 模型加载成功", modelType);
            } catch (Throwable t) {
                logger.error("创建 OnlineRecognizer 失败", t);
                throw new RuntimeException("初始化流式识别器失败", t);
            }

        } catch (Exception e) {
            logger.error("初始化流式识别器失败", e);
            throw new RuntimeException("初始化流式识别器失败", e);
        } catch (Error e) {
            logger.error("初始化流式识别器失败 (Error)", e);
            throw new RuntimeException("初始化流式识别器失败", e);
        }
    }

    /**
     * 初始化离线识别器（用于 SenseVoice、Whisper 等非流式模型）
     */
    private void initOfflineRecognizer() {
        String modelType = config.getModelType();

        try {
            // 获取模型目录路径（支持类路径和外部路径）
            String modelDirPath = getModelDirPath();
            logger.info("模型目录: {}", modelDirPath);

            String tokensPath = resolveModelFile("tokens.txt", modelDirPath);
            String encoderPath = resolveModelFile("encoder.int8.onnx", modelDirPath);
            String decoderPath = resolveModelFile("decoder.int8.onnx", modelDirPath);

            OfflineModelConfig.Builder configBuilder = OfflineModelConfig.builder()
                .setTokens(tokensPath)
                .setNumThreads(config.getNumThreads())
                .setDebug(false);

            if (modelType.contains("SENSE_VOICE") || modelType.contains("SENSEVOICE")) {
                OfflineSenseVoiceModelConfig senseVoiceConfig =
                    OfflineSenseVoiceModelConfig.builder()
                        .setModel(encoderPath)
                        .build();
                configBuilder.setSenseVoice(senseVoiceConfig);

            } else if (modelType.contains("WHISPER")) {
                OfflineWhisperModelConfig whisperConfig =
                    OfflineWhisperModelConfig.builder()
                        .setEncoder(encoderPath)
                        .setDecoder(decoderPath)
                        .build();
                configBuilder.setWhisper(whisperConfig);

            } else if (modelType.contains("PARAFORMER")) {
                OfflineParaformerModelConfig paraformerConfig =
                    OfflineParaformerModelConfig.builder()
                        .setModel(encoderPath)
                        .build();
                configBuilder.setParaformer(paraformerConfig);
            }

            OfflineModelConfig modelConfig = configBuilder.build();

            if (modelConfig.getSenseVoice() == null
                && modelConfig.getWhisper() == null
                && modelConfig.getParaformer() == null) {
                throw new RuntimeException("不支持的模型类型: " + modelType);
            }

            OfflineRecognizerConfig recognizerConfig =
                OfflineRecognizerConfig.builder()
                    .setOfflineModelConfig(modelConfig)
                    .build();

            offlineRecognizer = new OfflineRecognizer(recognizerConfig);

            logger.info("离线 {} 模型加载成功", modelType);

        } catch (Exception e) {
            throw new RuntimeException("初始化离线识别器失败", e);
        }
    }

    /**
     * 初始化 VAD（语音活动检测）
     */
    private void initVad() {
        String vadModelPath = config.getVadModelPath();

        String[] possiblePaths = {
            vadModelPath,
            "src/main/resources/models/silero_vad.onnx",
            "models/silero_vad.onnx",
            "target/classes/models/silero_vad.onnx",
            getAppDir() + "/models/silero_vad.onnx"
        };

        File vadModelFile = null;
        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists()) {
                vadModelFile = file;
                logger.info("找到 VAD 模型: {}", file.getAbsolutePath());
                break;
            }
        }

        if (vadModelFile == null) {
            logger.warn("VAD 模型文件不存在，跳过 VAD 初始化");
            return;
        }

        try {
            SileroVadModelConfig sileroVadConfig =
                SileroVadModelConfig.builder()
                    .setModel(vadModelFile.getAbsolutePath())
                    .setThreshold(0.3f)
                    .setMinSilenceDuration(0.2f)
                    .setMinSpeechDuration(0.1f)
                    .setWindowSize(512)
                    .build();

            VadModelConfig vadModelConfig =
                VadModelConfig.builder()
                    .setSileroVadModelConfig(sileroVadConfig)
                    .setSampleRate(sampleRate)
                    .setNumThreads(1)
                    .build();

            vad = new Vad(vadModelConfig);

            logger.info("VAD 初始化成功");

        } catch (Exception e) {
            logger.error("VAD 初始化失败", e);
        }
    }

    /**
     * 初始化标点符号模型
     */
    private void initPunctuation() {
        String punctModelDir = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12";

        String[] possiblePaths = {
            "src/main/resources/models/" + punctModelDir + "/model.onnx",
            "models/" + punctModelDir + "/model.onnx",
            getAppDir() + "/models/" + punctModelDir + "/model.onnx"
        };

        File punctModelFile = null;
        for (String path : possiblePaths) {
            File file = new File(path);
            if (file.exists()) {
                punctModelFile = file;
                logger.info("找到标点符号模型: {}", file.getAbsolutePath());
                break;
            }
        }

        if (punctModelFile == null) {
            logger.warn("标点符号模型文件不存在，跳过标点符号初始化");
            return;
        }

        try {
            OfflinePunctuationModelConfig modelConfig =
                OfflinePunctuationModelConfig.builder()
                    .setCtTransformer(punctModelFile.getAbsolutePath())
                    .setNumThreads(1)
                    .setDebug(false)
                    .build();

            OfflinePunctuationConfig config =
                OfflinePunctuationConfig.builder()
                    .setModel(modelConfig)
                    .build();

            punctuation = new OfflinePunctuation(config);

            logger.info("标点符号模型初始化成功");

        } catch (Exception e) {
            logger.error("标点符号模型初始化失败", e);
        }
    }

    /**
     * 添加标点符号到文本
     */
    public String addPunctuation(String text) {
        if (punctuation == null || text == null || text.isEmpty()) {
            return text;
        }
        try {
            return punctuation.addPunctuation(text);
        } catch (Exception e) {
            logger.warn("添加标点符号失败: {}", e.getMessage());
            return text;
        }
    }

    /**
     * 开始语音识别（流式）
     */
    @Override
    public void startListening(
        Consumer<String> onPartial,
        Consumer<String> onFinal,
        Consumer<String> onError
    ) {
        this.partialCallback = onPartial;
        this.finalCallback = onFinal;
        this.errorCallback = onError;
        this.isRunning = true;
        this.isPaused = false;

        executorService = Executors.newSingleThreadExecutor();
        recognitionThread = new Thread(() -> {
            try {
                if (useOnlineRecognition) {
                    startOnlineMicrophoneRecognition();
                } else {
                    startOfflineMicrophoneRecognition();
                }
            } catch (Exception e) {
                logger.error("语音识别错误: {}", e.getMessage(), e);
                if (errorCallback != null) {
                    errorCallback.accept(e.getMessage() != null ? e.getMessage() : "本地语音识别失败");
                }
            }
        }, "speech-recognition");
        recognitionThread.start();
    }

    /**
     * 停止语音识别
     */
    public void stopListening() {
        isRunning = false;
        isPaused = false;
        if (recognitionThread != null) {
            try {
                recognitionThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (executorService != null) {
            executorService.shutdown();
        }

        // 重置流式识别器状态
        if (onlineStream != null && onlineRecognizer != null) {
            onlineRecognizer.reset(onlineStream);
        }

        // 重置 VAD
        if (vad != null) {
            vad.reset();
        }
    }

    /**
     * 暂停识别
     */
    public void pauseListening() {
        isPaused = true;
    }

    /**
     * 恢复识别
     */
    public void resumeListening() {
        isPaused = false;
    }

    /**
     * 检查 VAD 是否启用
     */
    public boolean isVadEnabled() {
        return vad != null;
    }

    @Override
    public String getProviderDisplayName() {
        return "本地 Paraformer";
    }

    /**
     * 流式麦克风语音识别（用于 Paraformer 等流式模型）
     */
    private void startOnlineMicrophoneRecognition() throws Exception {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("不支持的音频格式");
        }

        onlineStream = onlineRecognizer.createStream();

        try (TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info)) {
            line.open(format);
            line.start();

            byte[] buffer = new byte[4096];
            int bytesRead;

            String lastResult = "";

            while (isRunning) {
                if (!isPaused) {
                    bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        float[] samples = bytesToFloat(buffer, bytesRead);

                        boolean hasSpeech = true;
                        if (vad != null) {
                            vad.acceptWaveform(samples);
                            hasSpeech = vad.isSpeechDetected();
                        }

                        if (hasSpeech) {
                            onlineStream.acceptWaveform(samples, sampleRate);

                            if (onlineRecognizer.isReady(onlineStream)) {
                                onlineRecognizer.decode(onlineStream);

                                OnlineRecognizerResult result = onlineRecognizer.getResult(onlineStream);
                                String text = result.getText();

                                if (text != null && !text.isEmpty()) {
                                    if (text.length() > lastResult.length()) {
                                        String incrementalText = text.substring(lastResult.length());
                                        lastResult = text;

                                        if (partialCallback != null && !incrementalText.isEmpty()) {
                                            partialCallback.accept(incrementalText);
                                        }
                                    } else if (!text.equals(lastResult)) {
                                        lastResult = text;
                                    }
                                }
                            }

                            if (onlineRecognizer.isEndpoint(onlineStream)) {
                                OnlineRecognizerResult endpointResult = onlineRecognizer.getResult(onlineStream);
                                String endpointText = endpointResult.getText();

                                if (endpointText != null && !endpointText.isEmpty()) {
                                    logger.info("端点检测: {}", endpointText);
                                }

                                onlineRecognizer.reset(onlineStream);
                                lastResult = "";
                            }
                        }
                    }
                } else {
                    Thread.sleep(10);
                }
            }

            OnlineRecognizerResult finalResult = onlineRecognizer.getResult(onlineStream);
            String finalText = finalResult.getText();

            if (finalCallback != null) {
                finalCallback.accept(finalText != null ? finalText.trim() : "");
            }
        }
    }

    /**
     * 离线麦克风语音识别（用于 SenseVoice、Whisper 等非流式模型）
     */
    private void startOfflineMicrophoneRecognition() throws Exception {
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new Exception("不支持的音频格式");
        }

        try (TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info)) {
            line.open(format);
            line.start();

            ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;

            while (isRunning) {
                if (!isPaused) {
                    bytesRead = line.read(buffer, 0, buffer.length);
                    if (bytesRead > 0) {
                        audioBuffer.write(buffer, 0, bytesRead);

                        // 每收集约2秒音频进行一次识别
                        if (audioBuffer.size() >= sampleRate * 2 * 2) {
                            byte[] audioData = audioBuffer.toByteArray();
                            String result = recognizeOffline(audioData);

                            if (partialCallback != null && result != null && !result.isEmpty()) {
                                partialCallback.accept(result);
                            }

                            // 保留最后500ms音频作为重叠
                            int overlapSize = sampleRate * 2 / 2;
                            if (audioBuffer.size() > overlapSize) {
                                byte[] remaining = new byte[overlapSize];
                                System.arraycopy(audioData, audioData.length - overlapSize, remaining, 0, overlapSize);
                                audioBuffer.reset();
                                audioBuffer.write(remaining);
                            }
                        }
                    }
                } else {
                    Thread.sleep(100);
                }
            }

            // 处理剩余音频
            if (audioBuffer.size() > 0) {
                String finalResult = recognizeOffline(audioBuffer.toByteArray());
                if (finalCallback != null) {
                    finalCallback.accept(finalResult);
                }
            } else {
                if (finalCallback != null) {
                    finalCallback.accept(null);
                }
            }
        }
    }

    /**
     * 执行离线语音识别
     */
    public String recognizeOffline(byte[] audioData) {
        if (offlineRecognizer == null) {
            logger.warn("离线识别器未初始化");
            return "";
        }

        try {
            float[] samples = bytesToFloat(audioData, audioData.length);

            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(samples, sampleRate);

            offlineRecognizer.decode(stream);
            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);

            return result.getText();

        } catch (Exception e) {
            logger.error("识别失败: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 执行语音识别（兼容旧API）
     */
    public String recognize(byte[] audioData) {
        if (useOnlineRecognition) {
            // 流式识别需要持续输入，这里返回空
            logger.warn("流式识别模式下请使用 startListening 方法");
            return "";
        } else {
            return recognizeOffline(audioData);
        }
    }

    /**
     * 获取模型目录路径
     * 支持从类路径（JAR 内）或外部目录加载
     */
    private String getModelDirPath() {
        String configPath = config.getModelPath();
        String appDir = getAppDir();

        // 尝试多个可能的路径（按优先级排序）
        String[] possiblePaths = {
            // 优先：应用程序根目录下的 models 文件夹（jpackage 打包后直接放在 app 目录旁）
            appDir + "/models",
            // jpackage 打包后 models 在 app 子目录内
            appDir + "/app/models",
            // 配置文件中的路径
            configPath,
            // 开发环境路径
            "src/main/resources/models",
            "models",
            "target/classes/models"
        };

        for (String path : possiblePaths) {
            File dir = new File(path);
            if (dir.exists() && dir.isDirectory()) {
                // 检查是否包含必要的模型文件
                if (new File(dir, "tokens.txt").exists()) {
                    logger.info("找到模型目录: {}", dir.getAbsolutePath());
                    return dir.getAbsolutePath();
                }
            }
        }

        throw new RuntimeException("无法找到模型目录，请检查配置: " + configPath);
    }

    /**
     * 解析模型文件路径
     * 支持从类路径或外部目录加载
     */
    private String resolveModelFile(String fileName, String modelDirPath) {
        File file = new File(modelDirPath, fileName);
        if (file.exists()) {
            return file.getAbsolutePath();
        }

        // 尝试其他变体（如 encoder.onnx vs encoder.int8.onnx）
        if (fileName.contains(".int8.")) {
            String alternativeName = fileName.replace(".int8.", ".");
            File alternativeFile = new File(modelDirPath, alternativeName);
            if (alternativeFile.exists()) {
                logger.info("使用非量化模型: {}", alternativeName);
                return alternativeFile.getAbsolutePath();
            }
        }

        throw new RuntimeException("模型文件不存在: " + fileName + "，目录: " + modelDirPath);
    }

    /**
     * 获取应用程序目录
     */
    private String getAppDir() {
        try {
            // 1. 首先尝试从 JAR 文件所在目录获取（最可靠）
            String path = getClass().getProtectionDomain().getCodeSource().getLocation().getPath();
            File file = new File(path);

            // 处理 JAR 文件路径中的特殊字符（如空格）
            path = java.net.URLDecoder.decode(path, "UTF-8");
            file = new File(path);

            if (file.isFile()) {
                // 从 JAR 文件向上查找安装目录
                File currentDir = file.getParentFile();
                while (currentDir != null) {
                    // 检查是否存在 models 目录或 app 目录（jpackage 结构）
                    if (new File(currentDir, "models").exists() ||
                        new File(currentDir, "app").exists()) {
                        logger.debug("从 JAR 路径找到应用程序目录: {}", currentDir.getAbsolutePath());
                        return currentDir.getAbsolutePath();
                    }
                    currentDir = currentDir.getParentFile();
                }
                // 如果没找到，返回 JAR 文件所在目录
                String jarDir = file.getParentFile().getAbsolutePath();
                logger.debug("未找到标志目录，使用 JAR 所在目录: {}", jarDir);
                return jarDir;
            }

            // 2. 如果 JAR 不存在（开发环境），尝试用户目录
            String userDir = System.getProperty("user.dir");
            if (userDir != null && !userDir.isEmpty()) {
                File testDir = new File(userDir);
                if (testDir.exists() && testDir.isDirectory()) {
                    logger.debug("使用 user.dir: {}", userDir);
                    return userDir;
                }
            }

            // 3. 返回当前目录
            logger.debug("返回当前目录: {}", file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (Exception e) {
            logger.warn("获取应用程序目录失败，使用当前目录: {}", e.getMessage());
            return ".";
        }
    }

    /**
     * 将字节数组转换为浮点数组
     */
    private float[] bytesToFloat(byte[] bytes, int length) {
        float[] result = new float[length / 2];
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < result.length; i++) {
            short sample = buffer.getShort();
            result[i] = sample / 32768.0f;
        }
        return result;
    }

    /**
     * 释放资源
     */
    public void release() {
        stopListening();

        if (onlineRecognizer != null) {
            onlineRecognizer.release();
            onlineRecognizer = null;
        }

        if (offlineRecognizer != null) {
            offlineRecognizer.release();
            offlineRecognizer = null;
        }

        if (vad != null) {
            vad.release();
            vad = null;
        }

        if (executorService != null) {
            executorService.shutdown();
        }
    }

    /**
     * 测试方法：从文件识别
     */
    public String recognizeFromFile(String filePath) throws Exception {
        if (offlineRecognizer == null) {
            logger.warn("离线识别器未初始化");
            return "";
        }

        try {
            WaveReader reader = new WaveReader(filePath);
            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(reader.getSamples(), reader.getSampleRate());

            offlineRecognizer.decode(stream);
            OfflineRecognizerResult result = offlineRecognizer.getResult(stream);

            return result.getText();

        } catch (Exception e) {
            logger.error("文件识别失败: {}", e.getMessage(), e);
            return "";
        }
    }
}
