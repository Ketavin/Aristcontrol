package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 语音输入管理器
 * 统一管理语音识别、AhaType服务和键盘注入
 */
public class VoiceInputManager {

    private static final Logger logger = LoggerFactory.getLogger(VoiceInputManager.class);

    private SpeechRecognitionService speechService;
    private QwenTextPolisher textPolisher;
    private KeyboardInjector keyboardInjector;
    private volatile boolean isEnabled = false;
    private volatile boolean isActivated = false;  // 服务是否已激活
    private volatile boolean isRecording = false;  // 是否正在录音
    private volatile boolean isAwaitingFinal = false;  // 已松键，等待识别/整理/注入完成
    private volatile boolean keyDownTriggered = false;  // 按键按下防抖标志
    private volatile boolean textPolishingEnabled = true;
    private final AtomicLong sessionGeneration = new AtomicLong();
    private final Object sessionStateLock = new Object();
    private volatile long activeSessionId;
    private KeyboardInjector.TargetSnapshot activeTargetSnapshot;
    private Consumer<String> resultCallback;
    private Consumer<String> partialCallback;
    private Consumer<String> statusCallback;

    public interface Consumer<T> {
        void accept(T t);
    }

    /**
     * 语音状态枚举
     */
    public enum VoiceStatus {
        IDLE("idle", "语音未启动"),           // 空闲状态
        READY("ready", "语音已就绪"),         // 服务已激活，等待按键
        RECORDING("recording", "语音输入中"), // 正在录音
        RECOGNIZING("recognizing", "识别中"), // 语音识别中
        PROCESSING("processing", "处理中"),   // 处理文本中
        ERROR("error", "识别失败"),           // 识别失败
        STOPPED("stopped", "语音已停止");    // 已停止

        private final String code;
        private final String message;

        VoiceStatus(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public String getMessage() { return message; }
    }

    /**
     * 初始化语音输入管理器
     */
    public void initialize() {
        try {
            ModelConfig config = ModelConfig.getInstance();
            speechService = SpeechRecognitionServices.create(config);
            speechService.initialize();
            textPolisher = new QwenTextPolisher(config);

            keyboardInjector = new KeyboardInjector();

            isEnabled = true;
            logger.info("VoiceInputManager 初始化成功，provider: {}", speechService.getProviderDisplayName());
        } catch (Exception e) {
            logger.error("VoiceInputManager 初始化失败: {}", e.getMessage(), e);
            isEnabled = false;
        }
    }

    /**
     * 启动语音输入（无回调版本）
     */
    public void startVoiceInput() {
        startVoiceInput(null, null);
    }

    /**
     * 启动语音输入（带结果回调版本）
     * @param callback 识别结果回调
     */
    public void startVoiceInput(Consumer<String> callback) {
        startVoiceInput(callback, null);
    }

    /**
     * 设置状态回调
     * @param statusCallback 状态变化回调
     */
    public void setStatusCallback(Consumer<String> statusCallback) {
        this.statusCallback = statusCallback;
    }

    /**
     * 通知状态变化
     */
    private void notifyStatus(VoiceStatus status) {
        notifyStatus(status, status.getMessage());
    }

    private void notifyStatus(VoiceStatus status, String message) {
        if (statusCallback != null) {
            try {
                statusCallback.accept(status.getCode() + ":" + message);
            } catch (Exception e) {
                logger.error("状态回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 启动语音输入（带完整回调版本）
     * 注意：这只是激活服务，需要调用 startRecording 才会开始录音
     * @param resultCallback 最终识别结果回调
     * @param partialCallback 中间结果回调
     */
    public void startVoiceInput(Consumer<String> resultCallback, Consumer<String> partialCallback) {
        if (!isEnabled || speechService == null) {
            logger.warn("语音输入未启用或未初始化");
            return;
        }

        synchronized (sessionStateLock) {
            if (isActivated) {
                logger.warn("语音输入服务已激活");
                return;
            }
            this.resultCallback = resultCallback;
            this.partialCallback = partialCallback;
            this.isActivated = true;
        }

        notifyStatus(VoiceStatus.READY);
        logger.info("语音输入服务已激活，等待按键触发...");
    }

    /**
     * 停止语音输入服务
     */
    public void stopVoiceInput() {
        boolean shouldStopRecording;
        synchronized (sessionStateLock) {
            if (!isActivated) {
                logger.warn("语音输入服务未激活");
                return;
            }

            // 与注入 commit 使用同一把锁：本方法返回后，旧会话不能再发送原生输入。
            this.isActivated = false;
            this.activeSessionId = sessionGeneration.incrementAndGet();
            this.isAwaitingFinal = false;
            this.activeTargetSnapshot = null;
            shouldStopRecording = isRecording;
        }

        // 如果正在录音，通知采集线程停止；识别仍在后台异步收尾。
        if (shouldStopRecording) {
            stopRecording();
        }

        logger.info("语音输入服务已停用");
    }

    /**
     * 开始录音（由按键触发）
     */
    public void startRecording() {
        synchronized (sessionStateLock) {
            if (!isActivated || speechService == null) {
                logger.warn("语音输入服务未激活，无法开始录音");
                return;
            }

            if (isRecording) {
                // 防抖处理：如果已经在录音且按键按下标志已设置，直接忽略
                if (keyDownTriggered) {
                    return;
                }
                logger.warn("已在录音中");
                return;
            }

            if (isAwaitingFinal) {
                logger.info("上一段语音仍在识别或注入，忽略新的按键按下");
                notifyStatus(VoiceStatus.PROCESSING, "上一段仍在处理中");
                return;
            }

            KeyboardInjector.TargetSnapshot target = keyboardInjector == null
                ? null
                : keyboardInjector.captureTargetSnapshot();
            if (target == null || !target.isUsable()) {
                logger.warn("无法捕获语音输入目标，拒绝开始录音以避免误投文本");
                notifyStatus(VoiceStatus.ERROR, "未找到可用的输入框");
                return;
            }

            this.isRecording = true;
            this.isAwaitingFinal = true;
            this.keyDownTriggered = true;
            long sessionId = sessionGeneration.incrementAndGet();
            this.activeSessionId = sessionId;
            this.activeTargetSnapshot = target;

            notifyStatus(VoiceStatus.RECORDING, "按住说话，松开后识别");
            logger.info("开始录音，provider: {}", speechService.getProviderDisplayName());

            // Keep activation/session creation atomic with provider startup so stopVoiceInput()
            // cannot invalidate the session immediately before a stale capture begins.
            speechService.startListening(
                text -> onPartialResult(sessionId, text),
                text -> onFinalResult(sessionId, text),
                message -> onRecognitionError(sessionId, message)
            );
        }
    }

    /**
     * 停止录音（由按键释放触发）
     */
    public void stopRecording() {
        SpeechRecognitionService service;
        synchronized (sessionStateLock) {
            if (!isRecording) {
                logger.warn("未在录音中");
                return;
            }
            service = speechService;
            this.isRecording = false;
            this.keyDownTriggered = false;  // 重置按键按下标志，允许下次触发
        }
        if (service != null) {
            notifyStatus(VoiceStatus.RECOGNIZING, service.getProviderDisplayName() + "识别中");
            logger.info("收到语音键释放，提交录音");
            service.stopListening();
            logger.info("停止录音");
        }
    }

    /**
     * 处理中间识别结果（Partial）
     */
    private void onPartialResult(long sessionId, String text) {
        Consumer<String> callback;
        synchronized (sessionStateLock) {
            if (!isCurrentSession(sessionId)) {
                logger.debug("忽略过期语音中间结果，session={}", sessionId);
                return;
            }
            callback = partialCallback;
        }
        logger.info("[语音识别中] {}", text);

        notifyStatus(VoiceStatus.RECOGNIZING, speechService.getProviderDisplayName() + "识别中");

        if (callback != null && text != null && !text.isEmpty()) {
            try {
                callback.accept(text);
            } catch (Exception e) {
                logger.error("中间结果回调失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 处理最终识别结果（Final）
     */
    private void onFinalResult(long sessionId, String text) {
        if (!isCurrentSession(sessionId)) {
            logger.info("忽略过期语音最终结果，session={}", sessionId);
            return;
        }
        logger.info("[语音识别完成] {}", text);

        notifyStatus(VoiceStatus.PROCESSING);

        if (text != null && !text.trim().isEmpty()) {
            // 添加标点符号
            String punctuatedText = speechService.addPunctuation(text.trim());
            logger.debug("添加标点符号后: {}", punctuatedText);

            KeyboardInjector.TargetSnapshot polishTarget;
            synchronized (sessionStateLock) {
                polishTarget = activeTargetSnapshot;
            }
            String processedText = processWithAhaType(punctuatedText, polishTarget);
            logger.debug("准备注入文本: {}", processedText);

            if (!isCurrentSession(sessionId)) {
                logger.info("文本整理期间会话已变化，取消注入，session={}", sessionId);
                return;
            }

            Consumer<String> callback;
            synchronized (sessionStateLock) {
                if (!isCurrentSession(sessionId)) {
                    logger.info("最终回调前会话已变化，取消注入，session={}", sessionId);
                    return;
                }
                callback = resultCallback;
            }
            if (callback != null) {
                try {
                    callback.accept(processedText);
                } catch (Exception e) {
                    logger.error("最终结果回调失败: {}", e.getMessage());
                }
            }

            injectText(sessionId, processedText);
        } else {
            logger.debug("识别结果为空或null");
        }

        synchronized (sessionStateLock) {
            if (!isCurrentSession(sessionId)) {
                logger.debug("会话已变化，不覆盖新会话状态，session={}", sessionId);
                return;
            }
            this.isAwaitingFinal = false;
            this.resultCallback = null;
            this.partialCallback = null;
            this.activeTargetSnapshot = null;
        }
        notifyStatus(VoiceStatus.STOPPED);
    }

    private void onRecognitionError(long sessionId, String message) {
        synchronized (sessionStateLock) {
            if (!isCurrentSession(sessionId)) {
                logger.debug("忽略过期语音错误，session={}: {}", sessionId, message);
                return;
            }
            this.isRecording = false;
            this.keyDownTriggered = false;
            this.isAwaitingFinal = false;
            this.resultCallback = null;
            this.partialCallback = null;
            this.activeTargetSnapshot = null;
        }
        logger.warn("语音识别失败: {}", message);
        notifyStatus(VoiceStatus.ERROR, message != null ? message : "识别失败，请重试");
    }

    private boolean isCurrentSession(long sessionId) {
        return isActivated && activeSessionId == sessionId;
    }

    /**
     * 使用 AhaType 整理文本
     */
    private String processWithAhaType(
        String text,
        KeyboardInjector.TargetSnapshot target
    ) {
        String normalized = QwenSpeechService.normalizeTranscript(text);
        if (!textPolishingEnabled || textPolisher == null) {
            return normalized;
        }
        QwenTextPolisher.Mode mode = selectPolishMode(target);
        notifyStatus(
            VoiceStatus.PROCESSING,
            mode == QwenTextPolisher.Mode.CHAT ? "微信聊天保真精修中" : "工作语言精修中"
        );
        logger.info("文本整理模式: {} target={}", mode, target == null ? "unknown" : target.executable());
        String polished = textPolisher.polishOrOriginal(normalized, mode);
        // 最终注入边界再做一次确定性清理，防止整理模型重新带入重复标点。
        return QwenSpeechService.normalizeTranscript(polished);
    }

    static QwenTextPolisher.Mode selectPolishMode(KeyboardInjector.TargetSnapshot target) {
        if (target == null || target.executable() == null) {
            return QwenTextPolisher.Mode.WORK;
        }
        String executable = target.executable();
        if ("Weixin.exe".equalsIgnoreCase(executable)
                || "WeChat.exe".equalsIgnoreCase(executable)) {
            return QwenTextPolisher.Mode.CHAT;
        }
        return QwenTextPolisher.Mode.WORK;
    }

    public void setTextPolishingEnabled(boolean enabled) {
        textPolishingEnabled = enabled;
        logger.info("语音轻度整理 {}", enabled ? "已启用" : "已关闭");
    }

    /**
     * 将文本注入到当前光标位置
     */
    private void injectText(long sessionId, String text) {
        if (keyboardInjector != null && text != null && !text.isEmpty()) {
            try {
                KeyboardInjector.TargetSnapshot target;
                synchronized (sessionStateLock) {
                    if (!isCurrentSession(sessionId)) {
                        logger.info("VoiceInputManager - 会话已取消，未进入注入，session={}", sessionId);
                        return;
                    }
                    target = activeTargetSnapshot;
                }
                if (target == null || !target.isUsable()) {
                    logger.warn("VoiceInputManager - 录音目标不可用，取消注入，session={}", sessionId);
                    return;
                }
                logger.debug("VoiceInputManager - 准备调用键盘注入器，文本长度: {}", text.length());
                KeyboardInjector.InjectionResult result = keyboardInjector.injectText(
                    text,
                    target,
                    commit -> commitInjectionIfCurrent(sessionId, commit)
                );
                if (result == KeyboardInjector.InjectionResult.CANCELLED) {
                    logger.info("VoiceInputManager - 会话已取消，未注入文本，session={}", sessionId);
                }
                logger.debug("VoiceInputManager - 键盘注入器调用完成");
            } catch (Exception e) {
                logger.error("VoiceInputManager - 文本注入失败: {}", e.getMessage(), e);
            }
        } else {
            logger.debug("VoiceInputManager - 跳过注入，键盘注入器为空或文本为空");
        }
    }

    private boolean commitInjectionIfCurrent(long sessionId, Runnable commit) {
        synchronized (sessionStateLock) {
            if (!isCurrentSession(sessionId)) {
                return false;
            }
            commit.run();
            return true;
        }
    }

    /**
     * 切换语音输入启用状态
     */
    public void toggleEnabled() {
        isEnabled = !isEnabled;
        logger.info("语音输入 {}", isEnabled ? "已启用" : "已禁用");
    }

    /**
     * 检查是否已启用
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * 检查服务是否已激活
     */
    public boolean isActivated() {
        return isActivated;
    }

    /**
     * 检查是否正在录音
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 关闭并释放资源
     */
    public void shutdown() {
        stopVoiceInput();
        if (speechService != null) {
            speechService.release();
        }
        if (keyboardInjector != null) {
            keyboardInjector.release();
        }
        this.isEnabled = false;
        this.isActivated = false;
        this.isRecording = false;
        logger.info("VoiceInputManager 已关闭");
    }

    /**
     * 获取语音服务实例
     */
    public SpeechRecognitionService getSpeechService() {
        return speechService;
    }

    /**
     * 获取键盘注入器实例
     */
    public KeyboardInjector getKeyboardInjector() {
        return keyboardInjector;
    }

    public boolean awaitTextInjectionReady(long timeoutMillis) {
        return keyboardInjector != null && keyboardInjector.awaitClipboardReady(timeoutMillis);
    }
}
