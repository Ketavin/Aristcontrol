package com.example.ahakey.service;

import com.example.ahakey.config.ModelConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.jna.platform.win32.Crypt32Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Qwen3-ASR provider that preserves AhaKey's push-to-talk interaction:
 * key down starts PCM capture, key up stops capture and submits one WAV.
 */
public final class QwenSpeechService implements SpeechRecognitionService {

    private static final Logger logger = LoggerFactory.getLogger(QwenSpeechService.class);
    private static final int MIN_AUDIO_BYTES = 3_200; // 100 ms at 16 kHz, mono, PCM16
    private static final int CAPTURE_BUFFER_BYTES = 4_096; // 128 ms at 16 kHz PCM16 mono
    private static final int HOT_PREROLL_MS = 300;
    private static final int RELEASE_TAIL_MS = 160;
    private static final int HOT_CAPTURE_READY_TIMEOUT_MS = 2_500;

    private final ModelConfig config;
    private final TerminologyManager terminologyManager;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private final Object captureLock = new Object();
    private volatile boolean running;
    private TargetDataLine preparedLine;
    private RollingPcmBuffer rollingPcm;
    private volatile boolean capturePumpRunning;
    private volatile Thread capturePumpThread;
    private ActiveCapture activeCapture;
    private volatile Thread recognitionThread;

    public QwenSpeechService(ModelConfig config) {
        this.config = config;
        this.terminologyManager = new TerminologyManager(config);
    }

    @Override
    public void initialize() throws Exception {
        URI.create(config.getQwenApiBase());
        Path keyPath = resolveKeyPath();
        if (!Files.isRegularFile(keyPath)) {
            throw new IOException("未找到百炼 API Key: " + keyPath);
        }
        char[] key = readApiKey();
        try {
            if (key.length < 40 || key[0] != 's' || key[1] != 'k' || key[2] != '-') {
                throw new IOException("百炼 API Key 格式无效");
            }
        } finally {
            Arrays.fill(key, '\0');
        }
        terminologyManager.initialize();
        rollingPcm = new RollingPcmBuffer(
            Math.max(MIN_AUDIO_BYTES, config.getSampleRate() * 2 * HOT_PREROLL_MS / 1_000)
        );
        prepareCaptureLine();
        logger.info("Qwen3-ASR 云端 provider 已就绪，模型: {}", config.getQwenModel());
    }

    private void prepareCaptureLine() {
        long startedAt = System.nanoTime();
        try {
            ensureHotCapture();
            logger.info(
                "Qwen3-ASR 持续热采集已就绪: {}ms deviceBuffer={}bytes rollingBuffer={}bytes({}ms)",
                elapsedMillis(startedAt),
                preparedLine.getBufferSize(),
                rollingPcm.capacity(),
                HOT_PREROLL_MS
            );
        } catch (Exception e) {
            shutdownCapturePump();
            logger.warn("Qwen3-ASR 持续热采集启动失败，将在按键时重试: {}", userFacingError(e));
        }
    }

    @Override
    public synchronized void startListening(
        Consumer<String> onPartial,
        Consumer<String> onFinal,
        Consumer<String> onError
    ) {
        if ((recognitionThread != null && recognitionThread.isAlive()) || activeCapture != null) {
            if (onError != null) {
                onError.accept("上一段语音仍在识别，请稍候");
            }
            return;
        }

        try {
            ensureHotCapture();
            synchronized (captureLock) {
                byte[] preroll = rollingPcm.snapshot();
                activeCapture = new ActiveCapture(preroll, onFinal, onError);
                Arrays.fill(preroll, (byte) 0);
                running = true;
                logger.info(
                    "Qwen3-ASR 热采集会话开始: preroll={}bytes({}ms) pump=true",
                    activeCapture.prerollBytes,
                    activeCapture.prerollBytes * 1_000L / (config.getSampleRate() * 2L)
                );
            }
        } catch (Exception e) {
            running = false;
            if (onError != null) {
                onError.accept(userFacingError(e));
            }
            return;
        }

    }

    @Override
    public void stopListening() {
        ActiveCapture session;
        synchronized (captureLock) {
            session = activeCapture;
            if (session == null || session.finishing) {
                return;
            }
            session.finishing = true;
            session.liveBytesAtKeyUp = session.liveBytes;
        }
        Thread finisher = new Thread(
            () -> finishCaptureAfterTail(session),
            "qwen3-asr-capture-finish"
        );
        finisher.setDaemon(true);
        finisher.start();
    }

    private void finishCaptureAfterTail(ActiveCapture session) {
        try {
            Thread.sleep(RELEASE_TAIL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        byte[] pcm;
        synchronized (captureLock) {
            if (activeCapture != session) {
                return;
            }
            activeCapture = null;
            running = false;
            pcm = session.audio.toByteArray();
        }
        recognitionThread = new Thread(
            () -> recognizePcm(pcm, session.liveBytesAtKeyUp, session.onFinal, session.onError),
            "qwen3-asr-recognition"
        );
        recognitionThread.setDaemon(true);
        recognitionThread.start();
    }

    private void recognizePcm(
        byte[] pcm,
        int liveBytesAtKeyUp,
        Consumer<String> onFinal,
        Consumer<String> onError
    ) {
        byte[] wav = null;
        try {
            if (liveBytesAtKeyUp < MIN_AUDIO_BYTES) {
                throw new IOException("录音太短，请按住按键说完后再松开");
            }

            logAudioQuality(pcm, config.getSampleRate());

            wav = pcmToWav(pcm, config.getSampleRate());
            String result = transcribeWav(wav);
            if (result.isBlank()) {
                throw new IOException("云端语音识别返回了空文本");
            }
            if (onFinal != null) {
                onFinal.accept(result.trim());
            }
        } catch (Exception e) {
            logger.error("Qwen3-ASR 识别失败: {}", e.getMessage());
            if (onError != null) {
                onError.accept(userFacingError(e));
            }
        } finally {
            if (pcm != null) {
                Arrays.fill(pcm, (byte) 0);
            }
            if (wav != null) {
                Arrays.fill(wav, (byte) 0);
            }
            recognitionThread = null;
        }
    }

    private TargetDataLine openCaptureLine() throws Exception {
        int sampleRate = config.getSampleRate();
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IOException("当前麦克风不支持 16 kHz 单声道录音");
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format, CAPTURE_BUFFER_BYTES);
        return line;
    }

    private synchronized void ensureHotCapture() throws Exception {
        if (preparedLine != null && preparedLine.isOpen()
                && capturePumpRunning && capturePumpThread != null && capturePumpThread.isAlive()) {
            return;
        }
        shutdownCapturePump();
        TargetDataLine line = openCaptureLine();
        CountDownLatch firstAudio = new CountDownLatch(1);
        preparedLine = line;
        capturePumpRunning = true;
        line.start();
        Thread pump = new Thread(() -> runCapturePump(line, firstAudio), "qwen3-asr-hot-capture");
        pump.setDaemon(true);
        capturePumpThread = pump;
        pump.start();
        if (!firstAudio.await(HOT_CAPTURE_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            shutdownCapturePump();
            throw new IOException("麦克风热采集在限定时间内没有收到声音数据");
        }
        if (!capturePumpRunning || !pump.isAlive() || !line.isOpen()) {
            shutdownCapturePump();
            throw new IOException("麦克风热采集线程未能保持运行");
        }
    }

    private void runCapturePump(TargetDataLine line, CountDownLatch firstAudio) {
        byte[] buffer = new byte[CAPTURE_BUFFER_BYTES];
        try {
            while (capturePumpRunning && line.isOpen()) {
                int bytesRead = line.read(buffer, 0, buffer.length);
                if (bytesRead <= 0) {
                    continue;
                }
                synchronized (captureLock) {
                    rollingPcm.append(buffer, 0, bytesRead);
                    ActiveCapture session = activeCapture;
                    if (session != null) {
                        session.audio.write(buffer, 0, bytesRead);
                        session.liveBytes += bytesRead;
                    }
                }
                firstAudio.countDown();
            }
        } catch (RuntimeException e) {
            if (capturePumpRunning) {
                logger.error("Qwen3-ASR 持续热采集异常: {}", userFacingError(e));
                ActiveCapture failedSession;
                synchronized (captureLock) {
                    failedSession = activeCapture;
                    activeCapture = null;
                    running = false;
                }
                if (failedSession != null && failedSession.onError != null) {
                    failedSession.onError.accept(userFacingError(e));
                }
            }
        } finally {
            Arrays.fill(buffer, (byte) 0);
            firstAudio.countDown();
        }
    }

    private synchronized void shutdownCapturePump() {
        capturePumpRunning = false;
        TargetDataLine line = preparedLine;
        preparedLine = null;
        if (line != null) {
            try {
                line.stop();
            } catch (RuntimeException ignored) {
            }
            try {
                line.close();
            } catch (RuntimeException ignored) {
            }
        }
        Thread pump = capturePumpThread;
        capturePumpThread = null;
        if (pump != null && pump != Thread.currentThread()) {
            try {
                pump.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (rollingPcm != null) {
            rollingPcm.clear();
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    String transcribeWav(byte[] wav) throws Exception {
        String audioData = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);
        String context = buildTranscriptionContext(terminologyManager.buildPromptGlossary());

        Map<String, Object> audioInput = new LinkedHashMap<>();
        audioInput.put("type", "input_audio");
        audioInput.put("input_audio", Map.of("data", audioData));

        List<Map<String, Object>> messages = List.of(
            Map.of("role", "system", "content", context),
            Map.of("role", "user", "content", List.of(audioInput))
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.getQwenModel());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("asr_options", Map.of("enable_itn", config.isQwenItnEnabled()));

        char[] keyChars = readApiKey();
        String apiKey = new String(keyChars);
        Arrays.fill(keyChars, '\0');
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getQwenApiBase()))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String responseText = new String(response.body(), StandardCharsets.UTF_8);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("百炼 API 返回 HTTP " + response.statusCode() + ": " + truncate(responseText, 240));
            }

            JsonNode root = objectMapper.readTree(responseText);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            String transcript = content.isTextual() ? content.asText() : "";
            logger.debug("Qwen3-ASR 原始转写: {}", transcript);
            String corrected = terminologyManager.applyHighConfidenceCorrections(transcript);
            return normalizeTranscript(corrected);
        } finally {
            apiKey = null;
        }
    }

    static String buildTranscriptionContext(String glossary) {
        String terms = glossary == null ? "" : glossary.trim();
        return "请忠实转写原话。以下是可能出现的专有名词、英文术语和人名："
            + terms
            + "。保留用户实际说出的中英文，不要解释或改写；不要省略重复词、口头语、语气词或看似冗余的短语。"
            + "标点应忠实反映实际语调和停顿：句中自然短停顿用“，”，明确疑问用“？”，明确强调、惊喜或兴奋用“！”，"
            + "明显犹豫、拖长或话未说完用中文省略号“……”。语气不明确时使用普通句号，"
            + "不要为了显得活泼而增加装饰性标点或表情。";
    }

    /**
     * Removes punctuation artifacts without rewriting dictated words. Qwen can
     * emit leading punctuation or repeat one sentence delimiter around a pause.
     * Keep mixed expressions such as "？！" and the Unicode ellipsis "……".
     */
    static String normalizeTranscript(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        final String asciiEllipsisMarker = "\uE000";
        String normalized = text.trim().replace("...", asciiEllipsisMarker);
        normalized = normalized.replaceFirst("^(?:[，,。]+|\\.(?=\\s|[\\p{IsHan}]))\\s*", "");
        normalized = normalized.replaceAll("，\\s*[,，]+", "，");
        normalized = normalized.replaceAll(",\\s*[,，]+", ",");
        normalized = normalized.replaceAll("([。.])(?:\\s*[。.])+", "$1");
        normalized = normalized.replaceAll("([！!])(?:\\s*[！!])+", "$1");
        normalized = normalized.replaceAll("([？?])(?:\\s*[？?])+", "$1");
        normalized = normalized.replaceAll("([。！？!?])\\s*[，,]+", "$1");
        normalized = normalized.replaceAll("[，,]\\s*([。！？!?])", "$1");
        return normalized.replace(asciiEllipsisMarker, "...").trim();
    }

    private static void logAudioQuality(byte[] pcm, int sampleRate) {
        int samples = pcm.length / 2;
        if (samples == 0 || sampleRate <= 0) {
            return;
        }

        double squareSum = 0;
        int peak = 0;
        int clipped = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int value = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int amplitude = Math.abs(value == Short.MIN_VALUE ? Short.MAX_VALUE : value);
            squareSum += (double) value * value;
            peak = Math.max(peak, amplitude);
            if (amplitude >= 32_440) {
                clipped++;
            }
        }

        double durationMs = samples * 1000.0 / sampleRate;
        double rms = Math.sqrt(squareSum / samples);
        double rmsDb = rms > 0 ? 20.0 * Math.log10(rms / 32_768.0) : -96.0;
        double peakDb = peak > 0 ? 20.0 * Math.log10(peak / 32_768.0) : -96.0;
        double clippedPercent = clipped * 100.0 / samples;
        logger.info(
            "录音质量: 时长={}ms, RMS={}dBFS, 峰值={}dBFS, 削波={}％",
            Math.round(durationMs), String.format(java.util.Locale.ROOT, "%.1f", rmsDb),
            String.format(java.util.Locale.ROOT, "%.1f", peakDb),
            String.format(java.util.Locale.ROOT, "%.3f", clippedPercent)
        );
    }

    private char[] readApiKey() throws IOException {
        byte[] encrypted = Base64.getDecoder().decode(Files.readString(resolveKeyPath(), StandardCharsets.UTF_8).trim());
        byte[] clear = Crypt32Util.cryptUnprotectData(encrypted);
        try {
            return new String(clear, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(clear, (byte) 0);
            Arrays.fill(encrypted, (byte) 0);
        }
    }

    private Path resolveKeyPath() {
        Path configured = Path.of(config.getQwenKeyPath());
        if (configured.isAbsolute()) {
            return configured;
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            throw new IllegalStateException("Windows LOCALAPPDATA 不可用");
        }
        return Path.of(localAppData).resolve(configured).normalize();
    }

    static byte[] pcmToWav(byte[] pcm, int sampleRate) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(pcm.length + 44)) {
            writeAscii(output, "RIFF");
            writeLeInt(output, pcm.length + 36);
            writeAscii(output, "WAVE");
            writeAscii(output, "fmt ");
            writeLeInt(output, 16);
            writeLeShort(output, 1);
            writeLeShort(output, 1);
            writeLeInt(output, sampleRate);
            writeLeInt(output, sampleRate * 2);
            writeLeShort(output, 2);
            writeLeShort(output, 16);
            writeAscii(output, "data");
            writeLeInt(output, pcm.length);
            output.write(pcm);
            return output.toByteArray();
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLeShort(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeLeInt(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private static String userFacingError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "云端识别失败，请检查网络后重试";
        }
        return truncate(message.replaceAll("[\\r\\n]+", " "), 120);
    }

    @Override
    public boolean isVadEnabled() {
        return false;
    }

    @Override
    public String addPunctuation(String text) {
        return text;
    }

    @Override
    public String getProviderDisplayName() {
        return "Arist Cloud";
    }

    @Override
    public synchronized void release() {
        stopListening();
        synchronized (captureLock) {
            if (activeCapture != null) {
                activeCapture.audio.reset();
                activeCapture = null;
            }
            running = false;
        }
        shutdownCapturePump();
    }

    static final class RollingPcmBuffer {
        private final byte[] data;
        private int writeIndex;
        private int size;

        RollingPcmBuffer(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            this.data = new byte[capacity];
        }

        synchronized void append(byte[] source, int offset, int length) {
            if (source == null || length <= 0) {
                return;
            }
            int safeOffset = Math.max(0, offset);
            int safeLength = Math.min(length, source.length - safeOffset);
            if (safeLength <= 0) {
                return;
            }
            if (safeLength >= data.length) {
                System.arraycopy(source, safeOffset + safeLength - data.length, data, 0, data.length);
                writeIndex = 0;
                size = data.length;
                return;
            }
            int first = Math.min(safeLength, data.length - writeIndex);
            System.arraycopy(source, safeOffset, data, writeIndex, first);
            int remaining = safeLength - first;
            if (remaining > 0) {
                System.arraycopy(source, safeOffset + first, data, 0, remaining);
            }
            writeIndex = (writeIndex + safeLength) % data.length;
            size = Math.min(data.length, size + safeLength);
        }

        synchronized byte[] snapshot() {
            byte[] result = new byte[size];
            int start = (writeIndex - size + data.length) % data.length;
            int first = Math.min(size, data.length - start);
            System.arraycopy(data, start, result, 0, first);
            if (first < size) {
                System.arraycopy(data, 0, result, first, size - first);
            }
            return result;
        }

        synchronized void clear() {
            Arrays.fill(data, (byte) 0);
            writeIndex = 0;
            size = 0;
        }

        int capacity() {
            return data.length;
        }
    }

    private static final class ActiveCapture {
        private final ByteArrayOutputStream audio = new ByteArrayOutputStream();
        private final Consumer<String> onFinal;
        private final Consumer<String> onError;
        private final int prerollBytes;
        private int liveBytes;
        private int liveBytesAtKeyUp;
        private boolean finishing;

        private ActiveCapture(
            byte[] preroll,
            Consumer<String> onFinal,
            Consumer<String> onError
        ) {
            if (preroll != null && preroll.length > 0) {
                audio.write(preroll, 0, preroll.length);
            }
            this.prerollBytes = preroll == null ? 0 : preroll.length;
            this.onFinal = onFinal;
            this.onError = onError;
        }
    }
}
