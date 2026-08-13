package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.protocol.AhaKeyProtocol;
import com.example.ahakey.protocol.AhaKeyResponseParser;
import com.example.ahakey.protocol.BleTcpPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BleManager {
    private static final Logger logger = LoggerFactory.getLogger(BleManager.class);
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 9000;
    private static final long RESPONSE_TIMEOUT_MS = 15000;
    private static final long CONNECTION_SUPERVISOR_INTERVAL_MS = 2500;
    private static final long BRIDGE_RECOVERY_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(8);

    private final String host;
    private final int port;
    private final BleCallback callback;

    private Socket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private Thread readerThread;
    private Thread connectionSupervisorThread;
    private final UsbHidTransport usbTransport = new UsbHidTransport();
    private final Object connectionStateLock = new Object();

    /** 串行化整个设备写入事务；reader 不得获取此锁。 */
    private final ReentrantLock operationLock = new ReentrantLock();
    /** 仅保护 ACK 收件箱和等待条件。 */
    private final ReentrantLock commandLock = new ReentrantLock();
    private final Condition responseReady = commandLock.newCondition();
    private final Map<Byte, ArrayDeque<byte[]>> pendingNotifyFrames = new HashMap<>();

    private volatile boolean isConnected;
    private volatile boolean isScanning;
    private volatile boolean bleDeviceConnected;
    private volatile boolean stopConnectionSupervisor = true;
    private final AtomicLong connectAttemptGeneration = new AtomicLong();
    private final AtomicLong lastBridgeRecoveryRequestNanos = new AtomicLong();
    private volatile Runnable bridgeRecoveryAction;
    private volatile BooleanSupplier bridgeConnectionGuard;
    /** Identifies the concrete TCP/USB transport; physical BLE changes do not alter it. */
    private final AtomicLong transportEpoch = new AtomicLong();
    private final AtomicLong connectionSession = new AtomicLong();
    private DeviceStatus cachedStatus = new DeviceStatus();
    private volatile long lastStatusUpdateTime = 0;  // 最后一次状态更新时间
    private final AtomicLong deviceStatusGeneration = new AtomicLong();
    private final AtomicLong liveDeviceStatusGeneration = new AtomicLong();
    private final AtomicLong liveStatusRequestCounter = new AtomicLong();
    private final AtomicLong lastLiveStatusResponseId = new AtomicLong();
    private final AtomicLong bleStatusGeneration = new AtomicLong();
    private volatile LightStatus cachedLightStatus = new LightStatus(-1, -1);

    public record LightStatus(int lightMode, int brightness) {
    }

    public interface BleCallback {
        void onConnected();
        void onDisconnected();
        void onStatusReceived(DeviceStatus status);
        void onError(String message);
    }

    @FunctionalInterface
    public interface ExclusiveOperation {
        void run() throws Exception;
    }

    public BleManager(BleCallback callback) {
        this(DEFAULT_HOST, DEFAULT_PORT, callback);
    }

    public BleManager(String host, int port, BleCallback callback) {
        this.host = host;
        this.port = port;
        this.callback = callback;
    }

    public static BleManager fromEnvironment(BleCallback callback) {
        String h = System.getenv("AHAKEY_BLE_HOST");
        String p = System.getenv("AHAKEY_BLE_PORT");
        int port = DEFAULT_PORT;
        if (p != null && !p.isBlank()) {
            try {
                port = Integer.parseInt(p.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return new BleManager(h != null && !h.isBlank() ? h.trim() : DEFAULT_HOST, port, callback);
    }

    /**
     * Installs an optional local bridge process recovery action. It is invoked
     * only after a current TCP connection attempt to the loopback bridge fails.
     */
    public void setBridgeRecoveryAction(Runnable recoveryAction) {
        this.bridgeRecoveryAction = recoveryAction;
    }

    /** Installs a local process/port ownership check used before TCP commit. */
    public void setBridgeConnectionGuard(BooleanSupplier bridgeConnectionGuard) {
        this.bridgeConnectionGuard = bridgeConnectionGuard;
    }

    /**
     * Runs a delayed bridge lifecycle action only while connection is still
     * desired. The action shares disconnect()'s state lock, so disconnect
     * cannot return and then be followed by a stale bridge relaunch.
     */
    public boolean runIfConnectionDesired(Runnable action) {
        synchronized (connectionStateLock) {
            if (stopConnectionSupervisor) {
                return false;
            }
            action.run();
            return true;
        }
    }

    public void connect() {
        stopConnectionSupervisor = false;
        startConnectionSupervisor();
        requestTransportConnection();
    }

    private void requestTransportConnection() {
        final long attempt;
        synchronized (connectionStateLock) {
            if (isScanning || isConnected || stopConnectionSupervisor) {
                return;
            }
            isScanning = true;
            attempt = connectAttemptGeneration.incrementAndGet();
        }

        Thread connector = new Thread(() -> {
            if (tryConnectUsb(attempt)) {
                return;
            }
            if (stopConnectionSupervisor || attempt != connectAttemptGeneration.get()) {
                return;
            }
            logger.info("Start connecting BLE bridge - {}:{}", host, port);
            Socket candidate = null;
            try {
                candidate = new Socket();
                candidate.connect(new InetSocketAddress(host, port), 1800);
                candidate.setTcpNoDelay(true);
                InputStream candidateInput = candidate.getInputStream();
                OutputStream candidateOutput = candidate.getOutputStream();
                verifyBridgeHandshake(candidate, candidateInput, candidateOutput);
                if (!isBridgeConnectionAllowed()) {
                    throw new IOException("BLE bridge process ownership verification failed");
                }
                long committedTransportEpoch;
                synchronized (connectionStateLock) {
                    if (stopConnectionSupervisor || attempt != connectAttemptGeneration.get()) {
                        candidate.close();
                        if (attempt == connectAttemptGeneration.get()) {
                            isScanning = false;
                        }
                        return;
                    }
                    closeTcpOnly();
                    socket = candidate;
                    outputStream = candidateOutput;
                    inputStream = candidateInput;
                    isConnected = true;
                    isScanning = false;
                    bleDeviceConnected = false;
                    connectionSession.incrementAndGet();
                    committedTransportEpoch = transportEpoch.incrementAndGet();
                    cachedStatus.setConnected(false);
                    cachedStatus.setDeviceName("Waiting for device");
                    if (cachedStatus.getBatteryLevel() < 0) {
                        cachedStatus.setBatteryLevel(50);
                    }
                    if (cachedStatus.getSwitchState() < 0) {
                        cachedStatus.setSwitchState(1);
                    }
                    startReader(committedTransportEpoch, inputStream);
                }
                logger.info("BLE bridge connected - {}:{}", host, port);
                if (transportEpoch.get() == committedTransportEpoch && isConnected) {
                    queryBridgeDeviceInfo();
                }
            } catch (IOException e) {
                boolean currentAttempt;
                synchronized (connectionStateLock) {
                    currentAttempt = attempt == connectAttemptGeneration.get();
                    if (currentAttempt) {
                        isScanning = false;
                        if (!isConnected) {
                            cachedStatus.setConnected(false);
                            bleDeviceConnected = false;
                        }
                    }
                }
                if (candidate != null) {
                    try { candidate.close(); } catch (IOException ignored) { }
                }
                if (currentAttempt) {
                    logger.warn("BLE bridge connect failed - {}:{}: {}", host, port, e.getMessage());
                    callback.onError("BLE bridge connect failed (" + host + ":" + port + "): " + e.getMessage());
                    requestBridgeRecovery(attempt);
                }
            }
        }, "device-connect");
        connector.setDaemon(true);
        connector.start();
    }

    private boolean isBridgeConnectionAllowed() {
        BooleanSupplier guard = bridgeConnectionGuard;
        if (!isLoopbackBridge() || guard == null) {
            return true;
        }
        try {
            return guard.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("BLE bridge connection guard failed: {}", e.getMessage());
            return false;
        }
    }

    private void verifyBridgeHandshake(Socket candidate, InputStream candidateInput,
                                       OutputStream candidateOutput) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_200);
        candidate.setSoTimeout(1_200);
        candidateOutput.write(BleTcpPacket.encode(BleTcpPacket.QUERY_BLE_STATUS, null));
        candidateOutput.flush();
        try {
            while (System.nanoTime() < deadline) {
                int remainingMillis = (int) Math.max(1L,
                    Math.min(1_200L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) + 1L));
                candidate.setSoTimeout(remainingMillis);
                byte[] header = candidateInput.readNBytes(3);
                if (header.length != 3) {
                    throw new IOException("BLE bridge closed during handshake");
                }
                int length = (header[1] & 0xFF) | ((header[2] & 0xFF) << 8);
                if (length < 0 || length > 8_192) {
                    throw new IOException("Invalid BLE bridge handshake frame length: " + length);
                }
                byte[] payload = candidateInput.readNBytes(length);
                if (payload.length != length) {
                    throw new IOException("Incomplete BLE bridge handshake frame");
                }
                if (header[0] == BleTcpPacket.BLE_STATUS_RESP && length >= 4) {
                    return;
                }
            }
            throw new IOException("BLE bridge handshake timed out");
        } finally {
            candidate.setSoTimeout(0);
        }
    }

    private void requestBridgeRecovery(long attempt) {
        Runnable recoveryAction = bridgeRecoveryAction;
        if (recoveryAction == null || !isLoopbackBridge()) {
            return;
        }

        long now = System.nanoTime();
        long previous = lastBridgeRecoveryRequestNanos.get();
        while (previous == 0 || now - previous >= BRIDGE_RECOVERY_COOLDOWN_NANOS) {
            if (lastBridgeRecoveryRequestNanos.compareAndSet(previous, now)) {
                Thread recoveryThread = new Thread(() -> {
                    try {
                        synchronized (connectionStateLock) {
                            if (stopConnectionSupervisor
                                || isConnected
                                || attempt != connectAttemptGeneration.get()) {
                                return;
                            }
                            // Linearize the recovery decision with disconnect(): once
                            // disconnect wins this lock, no later driver launch is allowed.
                            logger.info("Requesting bundled BLE bridge recovery");
                            recoveryAction.run();
                        }
                    } catch (RuntimeException e) {
                        logger.warn("Bundled BLE bridge recovery failed: {}", e.getMessage());
                    }
                }, "ble-bridge-recovery");
                recoveryThread.setDaemon(true);
                recoveryThread.start();
                return;
            }
            previous = lastBridgeRecoveryRequestNanos.get();
        }
    }

    private boolean isLoopbackBridge() {
        return port == DEFAULT_PORT && (
            DEFAULT_HOST.equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
        );
    }

    private void startConnectionSupervisor() {
        synchronized (connectionStateLock) {
            if (connectionSupervisorThread != null && connectionSupervisorThread.isAlive()) {
                return;
            }
            connectionSupervisorThread = new Thread(() -> {
                while (!stopConnectionSupervisor && !Thread.currentThread().isInterrupted()) {
                    try {
                        if (!isConnected) {
                            requestTransportConnection();
                        } else {
                            queryStatus();
                        }
                        Thread.sleep(CONNECTION_SUPERVISOR_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        logger.debug("连接监督器本轮检查失败: {}", e.getMessage());
                    }
                }
            }, "connection-supervisor");
            connectionSupervisorThread.setDaemon(true);
            connectionSupervisorThread.start();
        }
    }

    public void disconnect() {
        logger.info("=== 开始断开连接 ===");

        Thread supervisor;
        synchronized (connectionStateLock) {
            stopConnectionSupervisor = true;
            connectAttemptGeneration.incrementAndGet();
            supervisor = connectionSupervisorThread;
            connectionSupervisorThread = null;
            isConnected = false;
            isScanning = false;
            bleDeviceConnected = false;
            cachedStatus.setConnected(false);
            connectionSession.incrementAndGet();
            transportEpoch.incrementAndGet();
        }
        if (supervisor != null) {
            supervisor.interrupt();
        }

        // 第一步：立即标记断开状态，阻止新操作
        logger.debug("步骤1: 设置断开状态");

        // 第二步：唤醒等待响应的线程
        logger.debug("步骤2: 唤醒等待响应的线程");
        commandLock.lock();
        try {
            pendingNotifyFrames.clear();
            responseReady.signalAll();
            logger.debug("步骤2完成: 已唤醒等待线程");
        } finally {
            commandLock.unlock();
        }

        // 第三步：关闭 USB（会停止其内部 readerThread）
        logger.debug("步骤3: 关闭 USB 传输");
        try {
            usbTransport.close();
            logger.debug("步骤3完成: USB 传输已关闭");
        } catch (Exception e) {
            logger.error("步骤3失败: USB 关闭异常 - {}", e.getMessage(), e);
        }

        // 第四步：关闭 TCP 连接
        logger.debug("步骤4: 关闭 TCP 连接");
        try {
            closeTcpOnly();
            logger.debug("步骤4完成: TCP 连接已关闭");
        } catch (Exception e) {
            logger.error("步骤4失败: TCP 关闭异常 - {}", e.getMessage(), e);
        }

        // 第五步：通知回调（最后执行，避免回调中访问正在清理的资源）
        logger.debug("步骤5: 通知断开回调");
        try {
            callback.onDisconnected();
            logger.debug("步骤5完成: 回调通知成功");
        } catch (Exception e) {
            logger.error("步骤5失败: 回调异常 - {}", e.getMessage(), e);
        }

        logger.info("=== 断开连接完成 ===");
    }

    /**
     * Drops a broken transport but keeps the supervisor active, so a bridge or
     * keyboard that comes back later is recovered without another user click.
     */
    public void resetTransportForReconnect(String reason) {
        if (stopConnectionSupervisor) {
            return;
        }
        if (!usbTransport.isOpen()) {
            handleTransportLoss(reason);
            return;
        }

        long recoveryEpoch;
        synchronized (connectionStateLock) {
            if (stopConnectionSupervisor) {
                return;
            }
            logger.warn("USB transport reset for reconnect: {}", reason);
            isConnected = false;
            isScanning = true;
            bleDeviceConnected = false;
            cachedStatus.setConnected(false);
            connectionSession.incrementAndGet();
            recoveryEpoch = transportEpoch.incrementAndGet();
        }
        usbTransport.close();
        commandLock.lock();
        try {
            pendingNotifyFrames.clear();
            responseReady.signalAll();
        } finally {
            commandLock.unlock();
        }
        callback.onDisconnected();
        synchronized (connectionStateLock) {
            if (!stopConnectionSupervisor && transportEpoch.get() == recoveryEpoch) {
                isScanning = false;
            }
        }
        requestTransportConnection();
    }

    private void handleTransportLoss(String reason) {
        handleTransportLoss(reason, transportEpoch.get());
    }

    private void handleTransportLoss(String reason, long expectedTransportEpoch) {
        long recoveryEpoch;
        synchronized (connectionStateLock) {
            if (!isConnected || stopConnectionSupervisor
                || transportEpoch.get() != expectedTransportEpoch) {
                return;
            }
            logger.warn("BLE bridge transport lost: {}", reason);
            isConnected = false;
            isScanning = true;
            bleDeviceConnected = false;
            cachedStatus.setConnected(false);
            connectionSession.incrementAndGet();
            recoveryEpoch = transportEpoch.incrementAndGet();
            closeTcpOnly();
        }
        commandLock.lock();
        try {
            pendingNotifyFrames.clear();
            responseReady.signalAll();
        } finally {
            commandLock.unlock();
        }
        callback.onDisconnected();
        synchronized (connectionStateLock) {
            if (!stopConnectionSupervisor && transportEpoch.get() == recoveryEpoch) {
                isScanning = false;
            }
        }
        requestTransportConnection();
    }

    private void closeTcpOnly() {
        try {
            if (readerThread != null) {
                readerThread.interrupt();
            }
            if (inputStream != null) {
                inputStream.close();
            }
            if (outputStream != null) {
                outputStream.close();
            }
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            logger.warn("Close TCP connection failed: {}", e.getMessage());
        } finally {
            inputStream = null;
            outputStream = null;
            socket = null;
            readerThread = null;
        }
    }

    public void sendCommand(byte[] command) throws IOException {
        operationLock.lock();
        try {
            logger.debug("发送命令: {}", bytesToHex(command, Math.min(20, command.length)));
            if (ensureUsbConnected()) {
                usbTransport.sendCommand(command);
            } else {
                if (!bleDeviceConnected) {
                    throw new IOException("AhaKey 蓝牙设备未连接");
                }
                writePacket(BleTcpPacket.WRITE_COMMAND, command);
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void sendCommandExpecting(byte[] command, byte expectedCmd) throws Exception {
        operationLock.lock();
        try {
            commandLock.lock();
            try {
                clearPendingResponse(expectedCmd);
                long expectedSession = connectionSession.get();
                sendCommand(command);
                if (connectionSession.get() != expectedSession) {
                    throw new IOException("设备连接在命令发送期间发生变化");
                }
                waitForResponse(expectedCmd, expectedSession);
            } finally {
                commandLock.unlock();
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void writeData(byte[] chunk) throws IOException {
        operationLock.lock();
        try {
            logger.debug("发送数据块: {} 字节", chunk.length);
            if (ensureUsbConnected()) {
                usbTransport.sendData(chunk);
            } else {
                if (!bleDeviceConnected) {
                    throw new IOException("AhaKey 蓝牙设备未连接");
                }
                writePacket(BleTcpPacket.WRITE_DATA, chunk);
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void writeLargeData(long address, byte[] data) throws Exception {
        if (address % AhaKeyProtocol.OLED_CHUNK_SIZE != 0) {
            throw new IllegalArgumentException("地址必须 4K 对齐: " + address);
        }
        operationLock.lock();
        try {
            commandLock.lock();
            try {
                int offset = 0;
                int totalChunks = (int) Math.ceil((double) data.length / AhaKeyProtocol.OLED_CHUNK_SIZE);
                logger.info("开始写入大数据: 地址={}, 总长度={}, 分块数={}", address, data.length, totalChunks);
                while (offset < data.length) {
                    int chunkLen = Math.min(AhaKeyProtocol.OLED_CHUNK_SIZE, data.length - offset);
                    long chunkAddr = address + offset;
                    byte[] chunk = new byte[chunkLen];
                    System.arraycopy(data, offset, chunk, 0, chunkLen);

                    logger.debug("写入分块 {}/{}: 地址={}, 长度={}", (offset / AhaKeyProtocol.OLED_CHUNK_SIZE) + 1, totalChunks, chunkAddr, chunkLen);

                    clearPendingResponse(AhaKeyProtocol.CMD_PREPARE_WRITE);
                    long prepareSession = connectionSession.get();
                    sendCommand(AhaKeyProtocol.prepareWrite(chunkLen, chunkAddr));
                    if (connectionSession.get() != prepareSession) {
                        throw new IOException("设备连接在准备写入期间发生变化");
                    }
                    waitForResponse(AhaKeyProtocol.CMD_PREPARE_WRITE, prepareSession);

                    clearPendingResponse(AhaKeyProtocol.CMD_WRITE_RESULT);
                    long writeSession = connectionSession.get();
                    writeData(chunk);
                    if (connectionSession.get() != writeSession) {
                        throw new IOException("设备连接在数据写入期间发生变化");
                    }
                    waitForResponse(AhaKeyProtocol.CMD_WRITE_RESULT, writeSession);

                    offset += chunkLen;
                }
                logger.info("大数据写入完成: 地址={}, 总长度={}", address, data.length);
            } finally {
                commandLock.unlock();
            }
        } finally {
            operationLock.unlock();
        }
    }

    public AhaKeyResponseParser.PictureState readPictureState(int mode) throws Exception {
        operationLock.lock();
        try {
            commandLock.lock();
            try {
                clearPendingResponse(AhaKeyProtocol.CMD_READ_PIC_STATE);
                long expectedSession = connectionSession.get();
                sendCommand(AhaKeyProtocol.readPicState(mode));
                if (connectionSession.get() != expectedSession) {
                    throw new IOException("设备连接在读取图片状态期间发生变化");
                }
                byte[] frame = waitForResponse(AhaKeyProtocol.CMD_READ_PIC_STATE, expectedSession);
                AhaKeyResponseParser.CommandResponse parsed = AhaKeyResponseParser.parseCommandResponse(frame);
                if (parsed == null || parsed.status() != 0) {
                    return null;
                }
                return AhaKeyResponseParser.parsePictureState(parsed.payload());
            } finally {
                commandLock.unlock();
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 区分协议代际，避免把旧版 0x84/0x85 灯光命令发给新版任务 GIF 固件。
     * 0x86 + set=0xFF 在新版中只是查询；旧版会回无 payload 的普通 ACK。
     */
    public boolean supportsTaskPictureProtocol() throws Exception {
        operationLock.lock();
        try {
            commandLock.lock();
            try {
                clearPendingResponse(AhaKeyProtocol.CMD_TASK_PICTURE_SET);
                long expectedSession = connectionSession.get();
                sendCommand(AhaKeyProtocol.queryActiveTaskPictureSet(0));
                if (connectionSession.get() != expectedSession) {
                    throw new IOException("设备连接在协议能力检测期间发生变化");
                }
                byte[] frame = waitForResponse(AhaKeyProtocol.CMD_TASK_PICTURE_SET, expectedSession);
                AhaKeyResponseParser.CommandResponse parsed = AhaKeyResponseParser.parseCommandResponse(frame);
                byte[] payload = parsed == null ? null : parsed.payload();
                return parsed != null
                    && parsed.status() == 0
                    && payload != null
                    && payload.length >= 2
                    && (payload[0] & 0xFF) == 0
                    && (payload[1] & 0xFF) <= 1;
            } finally {
                commandLock.unlock();
            }
        } finally {
            operationLock.unlock();
        }
    }

    public void queryStatus() {
        if (!operationLock.tryLock()) {
            return;
        }
        try {
            if (ensureUsbConnected()) {
                sendCommand(AhaKeyProtocol.queryDeviceStatus());
                return;
            }
            // bridge 在线和物理 BLE 在线是两个状态。设备暂时离线时仍
            // 保持 TCP，并持续查询，等待 driver 按已保存设备自动回连。
            writePacket(BleTcpPacket.QUERY_BLE_STATUS, null);
            if (bleDeviceConnected) {
                writePacket(BleTcpPacket.WRITE_COMMAND, AhaKeyProtocol.queryDeviceStatus());
                writePacket(BleTcpPacket.QUERY_DEVICE_INFO, null);
            }
        } catch (IOException e) {
            callback.onError("查询设备状态失败: " + e.getMessage());
            if (!usbTransport.isOpen()) {
                handleTransportLoss(e.getMessage());
            }
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 查询设备实时状态并等待响应刷新缓存，最多等待 {@code timeoutMs} 毫秒。
     * 用于在读取 switchState 之前确保缓存是最新的。
     */
    public boolean queryStatusAndWait(long timeoutMs) {
        boolean locked = false;
        try {
            locked = operationLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!locked || !bleDeviceConnected) {
                logger.warn("queryStatusAndWait: 设备不可用或传输忙，按手动批准处理");
                return false;
            }

            // The TCP/BLE bridge provides a request-correlated live status response.
            // USB HID currently has no equivalent correlation, so a delayed periodic
            // response could otherwise be mistaken for this permission check. Keep
            // approval fail-closed on USB; the user's 507C Bluetooth path is unaffected.
            if (usbTransport.isOpen()) {
                logger.warn("queryStatusAndWait: USB 状态查询不支持请求关联，按手动批准处理");
                return false;
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            long awaitedBleGeneration = bleStatusGeneration.get();
            long liveRequestId = liveStatusRequestCounter.incrementAndGet();
            writePacket(BleTcpPacket.QUERY_BLE_STATUS, null);
            writePacket(BleTcpPacket.QUERY_LIVE_DEVICE_STATUS, encodeLongLittleEndian(liveRequestId));

            while (System.nanoTime() < deadlineNanos) {
                boolean liveStatusArrived = lastLiveStatusResponseId.get() == liveRequestId;
                boolean targetStatusArrived = bleStatusGeneration.get() != awaitedBleGeneration;
                if (targetStatusArrived && !bleDeviceConnected) {
                    logger.warn("queryStatusAndWait: 目标设备已离线，按手动批准处理");
                    return false;
                }
                if (targetStatusArrived && liveStatusArrived) {
                    logger.debug("queryStatusAndWait: 新状态已到达 switchState={}", cachedStatus.getSwitchState());
                    return true;
                }
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("queryStatusAndWait 刷新失败: {}", e.getMessage());
        } finally {
            if (locked) {
                operationLock.unlock();
            }
        }
        logger.warn("queryStatusAndWait: {}ms 内未收到新设备状态，按手动批准处理", timeoutMs);
        return false;
    }

    public void updateState(byte state) {
        try {
            sendCommand(AhaKeyProtocol.updateState(state));
        } catch (IOException e) {
            callback.onError("发送状态更新失败: " + e.getMessage());
        }
    }

    public void setLightEffect(byte effectCode) {
        try {
            sendCommand(AhaKeyProtocol.setLightEffect(effectCode));
        } catch (IOException e) {
            callback.onError("发送灯效指令失败: " + e.getMessage());
        }
    }

    public void setLightEffectAndWait(byte effectCode) throws Exception {
        sendCommandExpecting(
            AhaKeyProtocol.setLightEffect(effectCode),
            AhaKeyProtocol.CMD_SET_LIGHT_EFFECT
        );
    }

    public void setLightBrightness(int brightness) {
        try {
            sendCommand(AhaKeyProtocol.setLightBrightness(brightness));
        } catch (IOException e) {
            callback.onError("发送灯光亮度失败: " + e.getMessage());
        }
    }

    public void setLightBrightnessAndWait(int brightness) throws Exception {
        sendCommandExpecting(
            AhaKeyProtocol.setLightBrightness(brightness),
            AhaKeyProtocol.CMD_SET_LIGHT_BRIGHTNESS
        );
    }

    public void setAiLightConfig(int mode, byte[] effectCodes) {
        try {
            sendCommand(AhaKeyProtocol.setAiLightConfig(mode, effectCodes));
        } catch (IOException e) {
            callback.onError("发送 AI 状态灯效配置失败: " + e.getMessage());
        }
    }

    public void setAiLightConfigAndWait(int mode, byte[] effectCodes) throws Exception {
        sendCommandExpecting(
            AhaKeyProtocol.setAiLightConfig(mode, effectCodes),
            AhaKeyProtocol.CMD_SET_AI_LIGHT_CONFIG
        );
    }

    public LightStatus applyLightEffectAndVerify(byte effectCode, long timeoutMs) throws Exception {
        operationLock.lock();
        try {
            setLightEffectAndWait(effectCode);
            return queryLightStatusAndWait(effectCode & 0xFF, -1, timeoutMs);
        } finally {
            operationLock.unlock();
        }
    }

    public LightStatus applyLightBrightnessAndVerify(
        int brightness,
        byte previewEffect,
        long timeoutMs
    ) throws Exception {
        operationLock.lock();
        try {
            setLightBrightnessAndWait(brightness);
            setLightEffectAndWait(previewEffect);
            return queryLightStatusAndWait(previewEffect & 0xFF, brightness, timeoutMs);
        } finally {
            operationLock.unlock();
        }
    }

    public LightStatus saveLightConfigAndVerify(
        int mode,
        byte[] effectCodes,
        int brightness,
        long timeoutMs
    ) throws Exception {
        operationLock.lock();
        try {
            setAiLightConfigAndWait(mode, effectCodes);
            setLightBrightnessAndWait(brightness);
            return queryLightStatusAndWait(-1, brightness, timeoutMs);
        } finally {
            operationLock.unlock();
        }
    }

    /**
     * 刷新并核验设备灯光状态。Windows bridge 会缓存 0x00 状态帧而不转发，
     * 因此 BLE 模式下要在触发设备刷新后轮询 QUERY_DEVICE_INFO。
     * expectedLightMode / expectedBrightness 传 -1 表示不核验该字段。
     */
    public LightStatus queryLightStatusAndWait(
        int expectedLightMode,
        int expectedBrightness,
        long timeoutMs
    ) throws Exception {
        operationLock.lock();
        try {
            long beforeGeneration = deviceStatusGeneration.get();
            sendCommand(AhaKeyProtocol.queryDeviceStatus());
            long deadline = System.currentTimeMillis() + timeoutMs;
            long nextBridgeQuery = System.currentTimeMillis() + 80;
            while (System.currentTimeMillis() < deadline) {
                LightStatus observed = cachedLightStatus;
                boolean refreshed = deviceStatusGeneration.get() != beforeGeneration;
                boolean lightMatches = expectedLightMode < 0
                    || observed.lightMode() == expectedLightMode;
                boolean brightnessMatches = expectedBrightness < 0
                    || observed.brightness() == expectedBrightness;
                if (refreshed && lightMatches && brightnessMatches) {
                    return observed;
                }

                if (!usbTransport.isOpen() && System.currentTimeMillis() >= nextBridgeQuery) {
                    writePacket(BleTcpPacket.QUERY_DEVICE_INFO, null);
                    nextBridgeQuery = System.currentTimeMillis() + 80;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待设备灯光状态时被中断", e);
                }
            }
            throw new IOException(
                "设备灯光状态回读超时（当前灯效=" + cachedLightStatus.lightMode()
                    + "，亮度=" + cachedLightStatus.brightness() + "%）"
            );
        } finally {
            operationLock.unlock();
        }
    }

    public void setWorkMode(int mode) {
        try {
            sendCommand(AhaKeyProtocol.setWorkMode(mode));
        } catch (IOException e) {
            callback.onError("切换键盘模式失败: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isTargetDeviceConnected() {
        return isConnected && bleDeviceConnected;
    }

    public boolean isScanning() {
        return isScanning;
    }

    public DeviceStatus getCachedStatus() {
        return cachedStatus;
    }

    /** 在同一传输事务中执行一组写入，避免灯光、Hook 或整机同步互相穿插。 */
    public void runExclusive(ExclusiveOperation operation) throws Exception {
        operationLock.lock();
        try {
            operation.run();
        } finally {
            operationLock.unlock();
        }
    }

    public boolean isUsbConnected() {
        return usbTransport.isOpen();
    }

    public String selectPreferredTransport() throws IOException {
        operationLock.lock();
        try {
            if (ensureUsbConnected()) {
                return "USB";
            }
            if (isConnected && outputStream != null) {
                return "BLE";
            }
            throw new IOException("Device is not connected");
        } finally {
            operationLock.unlock();
        }
    }

    public long getLastStatusUpdateTime() {
        return lastStatusUpdateTime;
    }

    private void queryBridgeDeviceInfo() {
        try {
            if (usbTransport.isOpen()) {
                sendCommand(AhaKeyProtocol.queryDeviceStatus());
                return;
            }
            writePacket(BleTcpPacket.QUERY_BLE_STATUS, null);
        } catch (IOException ignored) {
        }
    }

    private boolean tryConnectUsb(long expectedAttempt) {
        try {
            if (stopConnectionSupervisor || !UsbHidTransport.isPresent()) {
                return false;
            }
            closeTcpOnly();
            usbTransport.open(this::onBleNotify);
            synchronized (connectionStateLock) {
                if (stopConnectionSupervisor || expectedAttempt != connectAttemptGeneration.get()) {
                    usbTransport.close();
                    return false;
                }
                isConnected = true;
                isScanning = false;
                bleDeviceConnected = true;
                cachedStatus.setConnected(true);
                cachedStatus.setDeviceName("AhaKey USB");
                cachedStatus.setBatteryLevel(100);
                connectionSession.incrementAndGet();
                transportEpoch.incrementAndGet();
                try {
                    callback.onConnected();
                } catch (RuntimeException callbackError) {
                    logger.warn("USB connected callback failed: {}", callbackError.getMessage());
                }
            }
            queryBridgeDeviceInfo();
            return true;
        } catch (Exception e) {
            logger.warn("USB HID connect failed: {}", e.getMessage());
            usbTransport.close();
            return false;
        }
    }

    private boolean ensureUsbConnected() {
        if (usbTransport.isOpen()) {
            return !stopConnectionSupervisor;
        }
        // Never switch a live TCP transaction to USB opportunistically. Closing the
        // socket here lets its reader report a stale loss after the USB commit.
        // The supervisor may still prefer USB when establishing a new transport.
        synchronized (connectionStateLock) {
            if (isConnected && outputStream != null) {
                return false;
            }
        }
        if (stopConnectionSupervisor || !UsbHidTransport.isPresent()) {
            return false;
        }
        try {
            closeTcpOnly();
            usbTransport.open(this::onBleNotify);
            synchronized (connectionStateLock) {
                if (stopConnectionSupervisor) {
                    usbTransport.close();
                    return false;
                }
                isConnected = true;
                isScanning = false;
                bleDeviceConnected = true;
                cachedStatus.setConnected(true);
                cachedStatus.setDeviceName("AhaKey USB");
                cachedStatus.setBatteryLevel(100);
                connectionSession.incrementAndGet();
                transportEpoch.incrementAndGet();
                try {
                    callback.onConnected();
                } catch (RuntimeException callbackError) {
                    logger.warn("USB reconnect callback failed: {}", callbackError.getMessage());
                }
            }
            return true;
        } catch (Exception e) {
            logger.warn("USB HID reconnect failed: {}", e.getMessage());
            usbTransport.close();
            return false;
        }
    }
    private void writePacket(byte type, byte[] data) throws IOException {
        if (!isConnected || outputStream == null) {
            throw new IOException("BLE 桥未连接");
        }
        int dataLen = data == null ? 0 : data.length;
        logger.debug("发送 TCP 包: type=0x{}, len={}", Integer.toHexString(type & 0xFF), dataLen);
        synchronized (outputStream) {
            outputStream.write(BleTcpPacket.encode(type, data));
            outputStream.flush();
        }
    }

    private byte[] waitForResponse(byte expectedCmd, long expectedSession) throws Exception {
        logger.debug("开始等待响应 0x{}", Integer.toHexString(expectedCmd & 0xFF));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESPONSE_TIMEOUT_MS);

        while (true) {
            if (!isConnected || !bleDeviceConnected || connectionSession.get() != expectedSession) {
                throw new IOException("设备连接已变化，已取消旧命令等待");
            }

            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new IOException("等待设备响应 0x" + Integer.toHexString(expectedCmd & 0xFF) + " 超时");
            }
            ArrayDeque<byte[]> queue = pendingNotifyFrames.get(expectedCmd);
            byte[] frame = queue == null ? null : queue.pollFirst();
            if (queue != null && queue.isEmpty()) {
                pendingNotifyFrames.remove(expectedCmd);
            }
            if (frame != null) {
                AhaKeyResponseParser.CommandResponse parsed = AhaKeyResponseParser.parseCommandResponse(frame);
                if (parsed == null) {
                    throw new IOException("无法解析设备响应帧");
                }
                logger.debug("收到期望的响应 0x{}", Integer.toHexString(expectedCmd & 0xFF));
                if (parsed.status() != 0) {
                    throw new IOException("设备返回错误码 " + parsed.status());
                }
                return frame;
            }
            responseReady.awaitNanos(remaining);
        }
    }

    /** 调用方必须持有 commandLock。 */
    private void clearPendingResponse(byte expectedCmd) {
        pendingNotifyFrames.remove(expectedCmd);
    }

    /** Caller commits readerThread while holding connectionStateLock. */
    private void startReader(long expectedTransportEpoch, InputStream readerInput) {
        Thread transportReader = new Thread(() -> {
            byte[] header = new byte[3];
            try {
                while (isConnected
                    && transportEpoch.get() == expectedTransportEpoch
                    && !Thread.currentThread().isInterrupted()) {
                    if (!readFully(readerInput, header, 3)) {
                        logger.warn("BLE 读取头部失败，连接可能已断开");
                        break;
                    }
                    int len = (header[1] & 0xFF) | ((header[2] & 0xFF) << 8);
                    byte[] body = len > 0 ? new byte[len] : new byte[0];
                    if (len > 0 && !readFully(readerInput, body, len)) {
                        logger.warn("BLE 读取数据失败，连接可能已断开");
                        break;
                    }
                    handlePacket(expectedTransportEpoch, header[0], body);
                }
            } catch (IOException e) {
                logger.warn("BLE 读取异常: {}", e.getMessage());
                if (isConnected && transportEpoch.get() == expectedTransportEpoch) {
                    callback.onError("BLE 桥连接断开: " + e.getMessage());
                }
            } finally {
                logger.info("BLE 读取线程退出");
                handleTransportLoss("reader stopped", expectedTransportEpoch);
            }
        }, "ble-tcp-reader");
        transportReader.setDaemon(true);
        readerThread = transportReader;
        transportReader.start();
    }

    private void handlePacket(long expectedTransportEpoch, byte type, byte[] data) {
        if (transportEpoch.get() != expectedTransportEpoch) {
            logger.debug("忽略旧传输会话的数据包: expected={}, current={}",
                expectedTransportEpoch, transportEpoch.get());
            return;
        }
        logger.debug("收到 TCP 包: type=0x{}, len={}", Integer.toHexString(type & 0xFF), data == null ? 0 : data.length);
        switch (type) {
            case BleTcpPacket.BLE_NOTIFY -> onBleNotify(data);
            case BleTcpPacket.DEVICE_INFO_RESP, BleTcpPacket.LIVE_DEVICE_STATUS_RESP -> {
                if (data != null && data.length >= 8) {
                    if (!usbTransport.isOpen() && !bleDeviceConnected) {
                        logger.debug("物理 BLE 离线，忽略 bridge 的陈旧设备信息缓存");
                        break;
                    }
                    long responseRequestId = -1;
                    byte[] statusPayload = data;
                    if (type == BleTcpPacket.LIVE_DEVICE_STATUS_RESP) {
                        if (data.length != 16) {
                            logger.warn("忽略长度错误的实时状态响应: {}", data.length);
                            break;
                        }
                        responseRequestId = decodeLongLittleEndian(data, 0);
                        statusPayload = new byte[8];
                        System.arraycopy(data, 8, statusPayload, 0, 8);
                    }
                    DeviceStatus parsed = AhaKeyProtocol.parseDeviceStatusPayload(statusPayload);
                    if (parsed == null) {
                        break;
                    }
                    int battery = parsed.getBatteryLevel();
                    int workMode = parsed.getWorkMode();
                    int switchState = parsed.getSwitchState();

                    // 验证数据有效性
                    boolean isValidBattery = battery >= 0 && battery <= 100;
                    boolean isValidWorkMode = workMode >= 0 && workMode <= 3;

                    logger.info("收到设备信息 - 电量: {}, 工作模式: {}, 灯效: {}, 亮度: {}, 拨杆: {}, 有效: {}",
                        battery, workMode, parsed.getLightMode(), parsed.getLightBrightness(), switchState,
                        isValidBattery && isValidWorkMode);

                    if (!isValidBattery || !isValidWorkMode) {
                        logger.warn("忽略无效设备信息负载: {}", bytesToHex(statusPayload, statusPayload.length));
                        break;
                    }

                    // 更新最后状态更新时间
                    lastStatusUpdateTime = System.currentTimeMillis();

                    // 更新缓存状态
                    cachedStatus.setBatteryLevel(battery);
                    cachedStatus.setSignal(parsed.getSignal());
                    cachedStatus.setFirmwareMain(parsed.getFirmwareMain());
                    cachedStatus.setFirmwareSub(parsed.getFirmwareSub());
                    cachedStatus.setWorkMode(workMode);
                    cachedStatus.setLightMode(parsed.getLightMode());
                    cachedStatus.setLightBrightness(parsed.getLightBrightness());
                    cachedStatus.setSwitchState(switchState);
                    cachedStatus.setConnected(true);
                    cachedStatus.setDeviceName("AhaKey Keyboard");

                    cachedLightStatus = new LightStatus(
                        cachedStatus.getLightMode(),
                        cachedStatus.getLightBrightness()
                    );
                    callback.onStatusReceived(cachedStatus);
                    // 发布 generation 前先让控制器同步更新跨线程审批真值。
                    deviceStatusGeneration.incrementAndGet();
                    if (type == BleTcpPacket.LIVE_DEVICE_STATUS_RESP) {
                        liveDeviceStatusGeneration.incrementAndGet();
                        lastLiveStatusResponseId.set(responseRequestId);
                    }
                }
            }
            case BleTcpPacket.BLE_STATUS_RESP -> {
                if (data != null && data.length > 0) {
                    // 解析BLE状态响应（参考Python的parse_status_response）
                    // 格式: [connected:1][name_len:1][name:N][mac_len:1][mac:N][is_target:1]
                    boolean reportedConnected = (data[0] & 0xFF) == 1;
                    String deviceName = "等待设备";
                    String deviceMac = "";
                    boolean isTarget = false;

                    int cursor = 1;
                    if (cursor < data.length) {
                        int nameLen = data[cursor++] & 0xFF;
                        if (nameLen <= data.length - cursor) {
                            if (nameLen > 0) {
                                deviceName = new String(data, cursor, nameLen, StandardCharsets.UTF_8);
                            }
                            cursor += nameLen;
                            if (cursor < data.length) {
                                int macLen = data[cursor++] & 0xFF;
                                if (macLen <= data.length - cursor) {
                                    if (macLen > 0) {
                                        deviceMac = new String(data, cursor, macLen, StandardCharsets.UTF_8);
                                    }
                                    cursor += macLen;
                                    if (cursor < data.length) {
                                        isTarget = data[cursor] != 0;
                                    }
                                }
                            }
                        }
                    }

                    boolean nameMatches = deviceName.toLowerCase(Locale.ROOT).startsWith("ahakey");
                    boolean bleConnected = reportedConnected && isTarget && nameMatches;

                    logger.info("BLE状态响应 - 已连接: {}, 目标设备: {}, 接受: {}, 设备名: {}, MAC: {}",
                        reportedConnected, isTarget, bleConnected, deviceName, deviceMac);

                    // 更新心跳时间戳（收到BLE状态响应也算作状态更新）
                    lastStatusUpdateTime = System.currentTimeMillis();

                    boolean wasConnected = bleDeviceConnected;
                    bleDeviceConnected = bleConnected;
                    cachedStatus.setConnected(bleConnected);
                    cachedStatus.setDeviceName(bleConnected ? deviceName : "等待设备");

                    // 如果是从断开变为连接，通知回调
                    if (bleConnected && !wasConnected) {
                        connectionSession.incrementAndGet();
                        callback.onConnected();
                    }

                    // 物理设备断开不等于 TCP bridge 断开。保留 reader 和
                    // supervisor，让 driver 后台重连 507C 后可无缝恢复。
                    if (!bleConnected && wasConnected) {
                        connectionSession.incrementAndGet();
                        commandLock.lock();
                        try {
                            pendingNotifyFrames.clear();
                            responseReady.signalAll();
                        } finally {
                            commandLock.unlock();
                        }
                        callback.onDisconnected();
                    }

                    // Never republish a stale switch value after disconnect; that would
                    // overwrite the controller's fail-closed manual approval state.
                    if (bleConnected) {
                        callback.onStatusReceived(cachedStatus);
                    }
                    bleStatusGeneration.incrementAndGet();
                }
            }
            default -> {
            }
        }
    }

    private void onBleNotify(byte[] data) {
        logger.debug("收到 BLE NOTIFY 通知，数据长度: {}", data.length);
        lastStatusUpdateTime = System.currentTimeMillis();

        // 打印前16字节的十六进制数据
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, 16); i++) {
            hex.append(String.format("%02X ", data[i]));
        }
        logger.debug("数据内容(前16字节): {}", hex);

        if (!AhaKeyProtocol.isValidFrame(data)) {
            logger.warn("无效的帧格式 - isValidFrame 返回 false");
            // 检查帧头帧尾
            if (data.length >= 4) {
                logger.debug("帧头: {}, 帧尾: {}",
                    String.format("%02X%02X", data[0], data[1]),
                    String.format("%02X%02X", data[data.length-2], data[data.length-1]));
            }
            return;
        }
        DeviceStatus status = AhaKeyProtocol.parseDeviceStatus(data);
        if (status != null) {
            logger.info("解析到设备状态 - 电量: {}, 工作模式: {}, 拨杆状态: {}",
                status.getBatteryLevel(),
                status.getWorkMode(),
                status.getSwitchState());
            if (usbTransport.isOpen()) {
                status.setDeviceName("AhaKey USB");
            } else if (cachedStatus.getDeviceName() != null && !cachedStatus.getDeviceName().isBlank()
                && !"等待设备".equals(cachedStatus.getDeviceName())) {
                status.setDeviceName(cachedStatus.getDeviceName());
            }
            status.setConnected(true);
            bleDeviceConnected = true;
            cachedStatus = status;
            cachedLightStatus = new LightStatus(status.getLightMode(), status.getLightBrightness());
            callback.onStatusReceived(status);
            deviceStatusGeneration.incrementAndGet();
            liveDeviceStatusGeneration.incrementAndGet();
            return;
        }
        logger.debug("parseDeviceStatus 返回 null");

        commandLock.lock();
        try {
            byte receivedCmd = data[2];
            ArrayDeque<byte[]> queue = pendingNotifyFrames.computeIfAbsent(
                receivedCmd,
                ignored -> new ArrayDeque<>()
            );
            if (queue.size() >= 8) {
                queue.removeFirst();
            }
            queue.addLast(data);
            responseReady.signalAll();
            logger.debug("收到响应 0x{}，唤醒等待线程", Integer.toHexString(receivedCmd & 0xFF));
        } finally {
            commandLock.unlock();
        }
    }

    private static String bytesToHex(byte[] bytes, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X", bytes[i]));
        }
        return sb.toString();
    }

    private static byte[] encodeLongLittleEndian(long value) {
        byte[] encoded = new byte[8];
        for (int i = 0; i < encoded.length; i++) {
            encoded[i] = (byte) ((value >>> (8 * i)) & 0xFF);
        }
        return encoded;
    }

    private static long decodeLongLittleEndian(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (long) (data[offset + i] & 0xFF) << (8 * i);
        }
        return value;
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, read, len - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }
}
