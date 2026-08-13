package com.example.ahakey.view;

import com.example.ahakey.config.ModelConfig;
import com.example.ahakey.app.StudioController;
import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.model.StudioState;
import com.example.ahakey.protocol.BleTcpPacket;
import com.example.ahakey.service.AgentManager;
import com.example.ahakey.service.HookDispatchServer;
import com.example.ahakey.service.HookInstaller;
import com.example.ahakey.service.VoiceInputManager;
import com.example.ahakey.util.Icons;
import com.example.ahakey.util.ProductPaths;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.scene.Scene;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javafx.scene.control.Alert;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.animation.AnimationTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TopBar extends VBox {
    private static final Logger logger = LoggerFactory.getLogger(TopBar.class);
    private final StudioController controller;
    private final DeviceStatus deviceStatus;
    private final StudioState studioState;
    private final AgentManager agentManager;
    private VoiceInputManager voiceInputManager;
    private TextArea logArea;
    private final HookInstaller hookInstaller;
    private final Object bleDriverLifecycleLock = new Object();
    private final AtomicBoolean bleDriverHealthCheckPending = new AtomicBoolean();

    private enum BridgeHealth {
        HEALTHY,
        UNHEALTHY,
        UNKNOWN
    }

    private record ListenerInspection(boolean successful, OptionalLong pid) {
        private static ListenerInspection success(OptionalLong pid) {
            return new ListenerInspection(true, pid);
        }

        private static ListenerInspection failure() {
            return new ListenerInspection(false, OptionalLong.empty());
        }
    }

    // 语音相关UI组件
    private Button voiceRecordButton;
    private VoiceStatusLamp voiceStatusLamp;
    private Label voiceStatusLabel;
    private Label voiceResultPreview;
    private volatile boolean isRecording = false;
    private volatile boolean voiceRunning = false;
    private FloatingVoiceNotification floatingNotification;  // 浮动通知窗口

    public TopBar(StudioController controller, DeviceStatus deviceStatus,
                  StudioState studioState, AgentManager agentManager) {
        this.controller = controller;
        this.deviceStatus = deviceStatus;
        this.studioState = studioState;
        this.agentManager = agentManager;
        this.hookInstaller = new HookInstaller(HookDispatchServer.DEFAULT_PORT, this::addLog);
        setSpacing(0);
        setPadding(new Insets(0));
        getStyleClass().add("top-bar");
        initContent();
    }

    /**
     * 设置语音输入管理器
     */
    public void setVoiceInputManager(VoiceInputManager voiceInputManager) {
        this.voiceInputManager = voiceInputManager;
        updateVoiceButtonState();
        if (voiceInputManager != null
                && voiceInputManager.isEnabled()
                && ModelConfig.getInstance().isVoiceAutoStartEnabled()) {
            if (Platform.isFxApplicationThread()) {
                startVoiceService();
            } else {
                Platform.runLater(this::startVoiceService);
            }
        }
    }

    private void initContent() {
        ImageView titleIcon = new ImageView(new Image(
            getClass().getResourceAsStream("/arist-ai-control.png")
        ));
        titleIcon.setFitWidth(24);
        titleIcon.setFitHeight(24);
        titleIcon.setPreserveRatio(true);
        Label titleLabel = new Label(ProductPaths.DISPLAY_NAME);
        titleLabel.getStyleClass().add("title");
        Label subtitleLabel = new Label("Voice · Keys · OLED");
        subtitleLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #7D8590;");
        VBox productName = new VBox(-2, titleLabel, subtitleLabel);
        HBox titleBox = new HBox(8);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.getChildren().addAll(titleIcon, productName);

        HBox infoPills = new HBox(10);
        infoPills.getChildren().addAll(
            new InfoPill(
                Bindings.createStringBinding(
                    () -> controller.isEffectivelyConnected() ? "已连接"
                        : (deviceStatus.isScanning() ? "扫描中" : "未连接"),
                    deviceStatus.isConnectedProperty(),
                    deviceStatus.isScanningProperty()
                ),
                deviceStatus.deviceNameProperty(),
                Bindings.createObjectBinding(
                    () -> controller.isEffectivelyConnected() ? AccentColor.GREEN : AccentColor.ORANGE,
                    deviceStatus.isConnectedProperty()
                )
            ),
            new InfoPill(
                Bindings.createStringBinding(() -> "电量"),
                Bindings.createStringBinding(
                    () -> controller.isEffectivelyConnected() ? deviceStatus.getBatteryLevel() + "%" : "—",
                    deviceStatus.isConnectedProperty(),
                    deviceStatus.batteryLevelProperty()
                ),
                Bindings.createObjectBinding(() -> AccentColor.BLUE)
            ),
            new InfoPill(
                Bindings.createStringBinding(() -> "拨杆"),
                Bindings.createStringBinding(deviceStatus::getSwitchTitle, deviceStatus.switchStateProperty()),
                Bindings.createObjectBinding(
                    () -> deviceStatus.isAutoApproval() ? AccentColor.MINT : AccentColor.INDIGO,
                    deviceStatus.switchStateProperty()
                )
            )
        );

        // 操作按钮
        Button connectButton = new Button();
        connectButton.getStyleClass().addAll("toolbar-button", "button-connect");
        connectButton.textProperty().bind(Bindings.createStringBinding(
            () -> deviceStatus.isConnected() ? "断开连接" : "连接设备",
            deviceStatus.isConnectedProperty()
        ));
        // 连接状态变化时切换按钮样式
        deviceStatus.isConnectedProperty().addListener((obs, oldVal, newVal) -> {
            connectButton.getStyleClass().removeAll("button-connect", "button-disconnect");
            connectButton.getStyleClass().addAll("toolbar-button", newVal ? "button-disconnect" : "button-connect");
        });
        connectButton.setOnAction(event -> {
            if (deviceStatus.isConnected()) {
                controller.userDisconnect();
            } else {
                controller.userConnect();
            }
        });

        // BLE 驱动按钮
        Button bleButton = new Button("BLE驱动");
        bleButton.getStyleClass().addAll("toolbar-button", "button-ble");
        bleButton.setOnAction(event -> handleBleButtonClick());

        // 语音启动按钮
        voiceRecordButton = new Button("启动语音输入");
        voiceRecordButton.getStyleClass().addAll("toolbar-button", "button-voice");
        voiceRecordButton.setOnAction(event -> toggleVoiceService());

        // 语音状态指示灯
        voiceStatusLamp = new VoiceStatusLamp();

        // 语音状态标签
        voiceStatusLabel = new Label("语音未启动");
        voiceStatusLabel.getStyleClass().add("voice-status");

        // 语音识别结果预览（单独一行）
        voiceResultPreview = new Label("");
        voiceResultPreview.getStyleClass().add("voice-preview");
        voiceResultPreview.setMinWidth(0);
        voiceResultPreview.setPrefWidth(240);
        voiceResultPreview.setMaxWidth(240);
        voiceResultPreview.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip voicePreviewTooltip = new Tooltip();
        voicePreviewTooltip.textProperty().bind(voiceResultPreview.textProperty());
        voiceResultPreview.setTooltip(voicePreviewTooltip);

        // 语音控制区域：仅包含识别结果预览
        VBox voiceControlBox = new VBox(4);
        voiceControlBox.setAlignment(Pos.CENTER_LEFT);
        voiceControlBox.setMinWidth(0);
        voiceControlBox.setPrefWidth(240);
        voiceControlBox.setMaxWidth(240);
        voiceControlBox.getChildren().addAll(voiceResultPreview);

        // 云端 ASR 不依赖本地模型包，按 provider 是否可用显示语音控件。
        boolean speechEnabled = ModelConfig.getInstance().isSpeechRecognitionEnabled();

        CheckBox polishToggle = new CheckBox("智能精修");
        polishToggle.setSelected(studioState.isAhaTypeEnabled());
        polishToggle.setTooltip(new Tooltip("微信保留自然语气，其他软件进行工作语言精修；失败时使用规则清理结果"));
        polishToggle.setOnAction(event -> studioState.toggleAhaType(polishToggle.isSelected()));
        studioState.ahaTypeEnabledProperty().addListener((obs, oldValue, enabled) -> {
            if (polishToggle.isSelected() != enabled) {
                polishToggle.setSelected(enabled);
            }
        });

        VBox configStatus = createStatusBox(
            Bindings.createBooleanBinding(agentManager::isEditingConfiguration, agentManager.bluetoothOwnerProperty()),
            Bindings.createStringBinding(
                () -> agentManager.isEditingConfiguration() ? "编辑配置中" : "键盘控制中",
                agentManager.bluetoothOwnerProperty()
            ),
            Bindings.createStringBinding(
                () -> agentManager.isEditingConfiguration()
                    ? "正在编辑配置"
                    : "键盘正常运行中",
                agentManager.bluetoothOwnerProperty()
            )
        );

        Button configModeButton = new Button();
        configModeButton.getStyleClass().add("button-prominent");
        configModeButton.textProperty().bind(Bindings.createStringBinding(controller::configurationModeButtonTitle,
            studioState.syncingProperty(),
            agentManager.bluetoothOwnerProperty()));
        configModeButton.disableProperty().bind(Bindings.createBooleanBinding(
            () -> studioState.syncingProperty().get() || agentManager.operationInProgressProperty().get(),
            studioState.syncingProperty(),
            agentManager.operationInProgressProperty()
        ));
        configModeButton.setOnAction(event -> controller.handleConfigurationModeButton());

        Menu moreMenu = new Menu("更多");
        Text moreIcon = Icons.moreHorizontal("16");
        moreMenu.setGraphic(moreIcon);

        MenuItem restoreDefaults = new MenuItem("恢复当前模式默认值");
        restoreDefaults.setOnAction(event -> studioState.restoreCurrentModeDefaults());
        MenuItem reconnect = new MenuItem("重新连接设备");
        reconnect.setOnAction(event -> {
            controller.userDisconnect();
            controller.userConnect();
        });
        MenuItem clearOled = new MenuItem("清空 OLED 预览");
        clearOled.setOnAction(event -> studioState.clearOledPreview());
        SeparatorMenuItem divider1 = new SeparatorMenuItem();
        MenuItem deviceInfo = new MenuItem("设备信息 · Hooks安装");
        deviceInfo.setOnAction(event -> showDeviceInfoDialog());
        MenuItem versionInfo = new MenuItem("查看版本号");
        versionInfo.setOnAction(event -> showVersionDialog());
        moreMenu.getItems().addAll(
            restoreDefaults,
            reconnect,
            clearOled,
            divider1,
            deviceInfo,
            versionInfo
        );

        MenuBar menuBar = new MenuBar(moreMenu);
        menuBar.setUseSystemMenuBar(false);
        menuBar.getStyleClass().add("toolbar-menu");

        // 将操作按钮放入统一的 HBox，语音状态也放在同一行
        HBox actionButtons = new HBox(10);
        actionButtons.setAlignment(Pos.CENTER);
        actionButtons.getChildren().addAll(
            connectButton, bleButton, voiceRecordButton, voiceStatusLamp, voiceStatusLabel, polishToggle
        );

        // 状态信息与操作按钮之间的固定间距
        Region spacer = new Region();
        spacer.setMinWidth(12);
        spacer.setPrefWidth(16);
        spacer.setMaxWidth(40);

        // 左侧工具区允许横向滚动；右侧“编辑配置”始终固定可见。
        HBox scrollableRow = new HBox(10);
        scrollableRow.setAlignment(Pos.CENTER);
        scrollableRow.setPadding(new Insets(6, 10, 6, 16));
        scrollableRow.setMinWidth(Region.USE_PREF_SIZE);
        scrollableRow.getChildren().addAll(titleBox, infoPills, spacer, actionButtons);
        if (speechEnabled) {
            scrollableRow.getChildren().addAll(voiceControlBox);
        } else {
            voiceRecordButton.setVisible(false);
            voiceRecordButton.setManaged(false);
            voiceStatusLamp.setVisible(false);
            voiceStatusLamp.setManaged(false);
            voiceStatusLabel.setVisible(false);
            voiceStatusLabel.setManaged(false);
            polishToggle.setVisible(false);
            polishToggle.setManaged(false);
            voiceResultPreview.setVisible(false);
            voiceResultPreview.setManaged(false);
        }

        // 右侧区域：状态、编辑按钮、菜单，强制单行水平排列
        HBox rightPanel = new HBox(15);
        rightPanel.setAlignment(Pos.CENTER_LEFT);
        rightPanel.setMinWidth(Region.USE_PREF_SIZE);
        rightPanel.setPadding(new Insets(6, 16, 6, 10));
        rightPanel.getChildren().addAll(configStatus, configModeButton, menuBar);

        ScrollPane scrollWrapper = new ScrollPane(scrollableRow);
        scrollWrapper.setFitToWidth(true);
        scrollWrapper.setFitToHeight(true);
        scrollWrapper.setMinWidth(0);
        scrollWrapper.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollWrapper.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollWrapper.setPannable(false);
        scrollWrapper.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        // 让 ScrollPane 内容背景透明
        scrollableRow.setStyle("-fx-background-color: transparent;");

        HBox fixedActionRow = new HBox();
        fixedActionRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(scrollWrapper, Priority.ALWAYS);
        fixedActionRow.getChildren().addAll(scrollWrapper, rightPanel);
        getChildren().add(fixedActionRow);
    }

    /**
     * 处理 BLE 驱动按钮点击
     * - 如果 BLE_tcp_driver.exe 已运行，弹窗提示
     * - 否则启动同级目录下的 BLE_tcp_driver.exe
     */
    private void handleBleButtonClick() {
        boolean alreadyHealthy = false;
        synchronized (bleDriverLifecycleLock) {
            if (isBleDriverRunning()) {
                BridgeHealth health = inspectBleBridgeHealth();
                if (health == BridgeHealth.HEALTHY) {
                    alreadyHealthy = true;
                } else if (health == BridgeHealth.UNHEALTHY) {
                    stopBleDriverProcess();
                    launchBleDriver();
                } else {
                    showBleDriverError("暂时无法确认 BLE 驱动监听进程，已保留现有进程并稍后重试");
                }
            } else {
                launchBleDriver();
            }
        }
        if (alreadyHealthy) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("BLE 驱动");
            alert.setHeaderText(null);
            alert.setContentText("BLE 驱动已打开");
            alert.showAndWait();
        }
    }

    /** Starts the bundled BLE bridge on app launch without showing an
     * "already open" dialog when it is healthy. */
    public void ensureBleDriverRunning() {
        synchronized (bleDriverLifecycleLock) {
            Optional<ProcessHandle> bundledDriver = findBundledBleDriverProcess();
            BridgeHealth health = inspectBleBridgeHealth();
            if (health == BridgeHealth.UNKNOWN) {
                logger.warn("BLE bridge health inspection unavailable; preserving current processes");
                return;
            }
            if (bundledDriver.isPresent() && health == BridgeHealth.HEALTHY) {
                return;
            }
            if (bundledDriver.isPresent()) {
                if (!bleDriverHealthCheckPending.compareAndSet(false, true)) {
                    return;
                }
                long expectedPid = bundledDriver.get().pid();
                // Windows 登录时 driver 进程往往先出现、9000 端口稍后才监听。
                // 给正常冷启动留出时间，避免 Studio 与启动项互相 taskkill。
                Thread delayedHealthCheck = new Thread(() -> {
                    try {
                        Thread.sleep(5_000);
                        controller.getBleManager().runIfConnectionDesired(() -> {
                            synchronized (bleDriverLifecycleLock) {
                                Optional<ProcessHandle> current = findBundledBleDriverProcess();
                                BridgeHealth delayedHealth = inspectBleBridgeHealth();
                                if (current.isPresent()
                                        && current.get().pid() == expectedPid
                                        && delayedHealth == BridgeHealth.UNHEALTHY) {
                                    stopBleDriverProcess(current.get());
                                    launchBleDriver();
                                }
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        bleDriverHealthCheckPending.set(false);
                    }
                }, "ble-driver-health-check");
                delayedHealthCheck.setDaemon(true);
                delayedHealthCheck.start();
                return;
            }
            launchBleDriver();
        }
    }

    /** Confirms that port 9000 belongs to this installation and speaks the bridge protocol. */
    public boolean isBundledBridgeReady() {
        synchronized (bleDriverLifecycleLock) {
            return inspectBleBridgeHealth() == BridgeHealth.HEALTHY;
        }
    }

    private BridgeHealth inspectBleBridgeHealth() {
        File expected = findBundledBleDriverExecutable();
        if (expected == null) {
            return BridgeHealth.UNKNOWN;
        }
        ListenerInspection inspection = inspectBleBridgeListener();
        if (!inspection.successful()) {
            return BridgeHealth.UNKNOWN;
        }
        OptionalLong listenerPid = inspection.pid();
        if (listenerPid.isEmpty()) {
            return BridgeHealth.UNHEALTHY;
        }
        Optional<ProcessHandle> listener = ProcessHandle.of(listenerPid.getAsLong());
        if (listener.isEmpty()) {
            return BridgeHealth.UNKNOWN;
        }
        if (!isBundledBleDriverProcess(listener.get(), expected)) {
            return BridgeHealth.UNHEALTHY;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 9000), 800);
            socket.getOutputStream().write(BleTcpPacket.encode(BleTcpPacket.QUERY_BLE_STATUS, null));
            socket.getOutputStream().flush();

            InputStream input = socket.getInputStream();
            long deadline = System.nanoTime() + 800_000_000L;
            while (System.nanoTime() < deadline) {
                long remainingMillis = Math.max(1L,
                    (deadline - System.nanoTime() + 999_999L) / 1_000_000L);
                socket.setSoTimeout((int) Math.min(800L, remainingMillis));
                byte[] header = input.readNBytes(3);
                if (header.length != 3) {
                    return BridgeHealth.UNHEALTHY;
                }
                int length = (header[1] & 0xFF) | ((header[2] & 0xFF) << 8);
                if (length < 0 || length > 512) {
                    return BridgeHealth.UNHEALTHY;
                }
                byte[] payload = input.readNBytes(length);
                if (payload.length != length) {
                    return BridgeHealth.UNHEALTHY;
                }
                if (header[0] == BleTcpPacket.BLE_STATUS_RESP && length >= 4) {
                    return BridgeHealth.HEALTHY;
                }
            }
            return BridgeHealth.UNHEALTHY;
        } catch (Exception e) {
            return BridgeHealth.UNHEALTHY;
        }
    }

    private void stopBleDriverProcess() {
        File expected = findBundledBleDriverExecutable();
        if (expected == null) {
            return;
        }
        List<ProcessHandle> matches = ProcessHandle.allProcesses()
            .filter(process -> isBundledBleDriverProcess(process, expected))
            .toList();
        for (ProcessHandle process : matches) {
            stopBleDriverProcess(process);
        }
    }

    private void stopBleDriverProcess(ProcessHandle process) {
        process.destroy();
        long deadline = System.nanoTime() + 1_500_000_000L;
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            long forcedDeadline = System.nanoTime() + 1_000_000_000L;
            while (process.isAlive() && System.nanoTime() < forcedDeadline) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 检查 BLE_tcp_driver.exe 是否正在运行
     */
    private boolean isBleDriverRunning() {
        return findBundledBleDriverProcess().isPresent();
    }

    private Optional<ProcessHandle> findBundledBleDriverProcess() {
        File expected = findBundledBleDriverExecutable();
        return expected == null ? Optional.empty() : ProcessHandle.allProcesses()
            .filter(process -> isBundledBleDriverProcess(process, expected))
            .findFirst();
    }

    /**
     * 启动同级目录下的 BLE_tcp_driver.exe
     */
    private void launchBleDriver() {
        try {
            File bleExe = findBundledBleDriverExecutable();
            if (bleExe != null) {
                if (!prepareBridgePortForBundledDriver(bleExe)) {
                    return;
                }
                final File finalBleExe = bleExe;
                ProcessBuilder pb = new ProcessBuilder(finalBleExe.getAbsolutePath());
                pb.directory(finalBleExe.getParentFile());
                pb.start();

                // 短暂延迟后再次检查，给用户反馈
                Thread launchCheck = new Thread(() -> {
                    try {
                        Thread.sleep(1000);
                        Platform.runLater(() -> {
                            if (isBleDriverRunning()) {
                                // 启动成功，无需额外提示
                            } else {
                                showAlert("BLE 驱动", "BLE 驱动启动失败，请手动运行: " + finalBleExe.getAbsolutePath());
                            }
                        });
                    } catch (Exception ignored) {}
                }, "ble-driver-launch-check");
                launchCheck.setDaemon(true);
                launchCheck.start();
            } else {
                showBleDriverError("未找到随应用安装的 BLE_tcp_driver.exe");
            }
        } catch (Exception e) {
            showBleDriverError("启动失败: " + e.getMessage());
        }
    }

    private File findBundledBleDriverExecutable() {
        String appDir = System.getProperty("user.dir");
        try {
            Path jarPath = Paths.get(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jarPath.toString().endsWith(".jar")) {
                appDir = jarPath.getParent().toString();
            }
        } catch (Exception ignored) {
        }

        File appDirFile = new File(appDir);
        File[] candidates = {
            new File(appDir, "BLE_tcp_driver.exe"),
            appDirFile.getParentFile() != null
                ? new File(appDirFile.getParentFile(), "BLE_tcp_driver.exe") : null,
            new File(System.getProperty("user.dir"), "BLE_tcp_driver.exe"),
            new File(appDir, "app/BLE_tcp_driver.exe")
        };
        for (File candidate : candidates) {
            if (candidate != null && candidate.isFile()) {
                return candidate.getAbsoluteFile();
            }
        }
        return null;
    }

    private boolean isBundledBleDriverProcess(ProcessHandle process, File expected) {
        return process.info().command()
            .map(command -> sameWindowsPath(command, expected.getAbsolutePath()))
            .orElse(false);
    }

    private boolean prepareBridgePortForBundledDriver(File expected) {
        ListenerInspection inspection = inspectBleBridgeListener();
        if (!inspection.successful()) {
            showBleDriverError("暂时无法检查本机端口 9000，未启动第二个 BLE 驱动进程");
            return false;
        }
        OptionalLong listenerPid = inspection.pid();
        if (listenerPid.isEmpty()) {
            return true;
        }
        Optional<ProcessHandle> owner = ProcessHandle.of(listenerPid.getAsLong());
        if (owner.isEmpty()) {
            showBleDriverError("无法确认本机端口 9000 的监听进程（PID "
                + listenerPid.getAsLong() + "），未自动覆盖");
            return false;
        }
        if (isBundledBleDriverProcess(owner.get(), expected)) {
            logger.info("Bundled BLE bridge already owns port 9000 (pid={})", owner.get().pid());
            return false;
        }
        if (isLegacyBleDriverProcess(owner.get())) {
            logger.warn("Stopping legacy BLE bridge on port 9000 (pid={})", owner.get().pid());
            stopBleDriverProcess(owner.get());
            ListenerInspection afterStop = inspectBleBridgeListener();
            return afterStop.successful() && afterStop.pid().isEmpty();
        }
        showBleDriverError("本机端口 9000 被其他程序占用（PID "
            + listenerPid.getAsLong() + "），未自动终止该程序");
        return false;
    }

    private boolean isLegacyBleDriverProcess(ProcessHandle process) {
        return process.info().command()
            .map(command -> "BLE_tcp_driver.exe".equalsIgnoreCase(new File(command).getName()))
            .orElse(false);
    }

    private ListenerInspection inspectBleBridgeListener() {
        Process process = null;
        Path outputFile = null;
        try {
            outputFile = Files.createTempFile("ahakey-netstat-", ".txt");
            process = new ProcessBuilder("netstat", "-ano", "-p", "tcp")
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
            if (!process.waitFor(1_000, TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                return ListenerInspection.failure();
            }
            // ISO-8859-1 is deliberate: netstat headers follow the OEM code page,
            // while the addresses/PID columns we parse are ASCII-compatible.
            for (String line : Files.readAllLines(outputFile, StandardCharsets.ISO_8859_1)) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length < 4
                        || !"127.0.0.1:9000".equals(columns[1])
                        || !columns[2].endsWith(":0")) {
                    continue;
                }
                try {
                    return ListenerInspection.success(
                        OptionalLong.of(Long.parseLong(columns[columns.length - 1]))
                    );
                } catch (NumberFormatException ignored) {
                }
            }
            return ListenerInspection.success(OptionalLong.empty());
        } catch (IOException e) {
            logger.debug("Unable to inspect BLE bridge listener: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (outputFile != null) {
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ignored) {
                }
            }
        }
        return ListenerInspection.failure();
    }

    private boolean sameWindowsPath(String first, String second) {
        try {
            return new File(first).getCanonicalPath().equalsIgnoreCase(new File(second).getCanonicalPath());
        } catch (IOException e) {
            return new File(first).getAbsolutePath().equalsIgnoreCase(new File(second).getAbsolutePath());
        }
    }

    private void showBleDriverError(String message) {
        logger.error("BLE driver error: {}", message);
        Platform.runLater(() -> showAlert("BLE 驱动", message));
    }

    /**
     * 显示警告弹窗
     */
    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    /**
     * 切换语音服务状态（启动/停止）
     */
    private void toggleVoiceService() {
        if (voiceInputManager == null) {
            setVoiceStatus("error", "语音服务未初始化");
            return;
        }

        if (voiceRunning) {
            stopVoiceService();
        } else {
            startVoiceService();
        }
    }

    /**
     * 启动语音服务
     */
    private void startVoiceService() {
        voiceRunning = true;
        updateVoiceButtonState();
        setVoiceStatus("starting", "语音启动中");

        if (!voiceInputManager.awaitTextInjectionReady(1_500)) {
            voiceRunning = false;
            updateVoiceButtonState();
            setVoiceStatus("error", "文本注入服务初始化失败");
            logger.error("VOICE_STARTUP_FAILED: clipboard STA did not become ready");
            return;
        }

        // 创建浮动通知窗口（单例模式）
        floatingNotification = FloatingVoiceNotification.getInstance();

        // 设置状态回调（同时更新UI和浮动通知）
        voiceInputManager.setStatusCallback(status -> {
            // 状态格式: "code:message"
            String[] parts = status.split(":", 2);
            String code = parts[0];
            String message = parts.length > 1 ? parts[1] : code;

            Platform.runLater(() -> {
                // 更新 TopBar 状态
                setVoiceStatus(code, message);

                // 更新浮动通知
                if (floatingNotification != null) {
                    floatingNotification.updateStatus(code, message);
                }
            });
        });

        // 启动语音输入管理器
        voiceInputManager.startVoiceInput(result -> {
            Platform.runLater(() -> {
                voiceResultPreview.setText(result);
            });
        }, partialResult -> {
            Platform.runLater(() -> {
                String current = voiceResultPreview.getText();
                voiceResultPreview.setText(current + partialResult);
            });
        });

        // Install the low-level key hook only after callbacks are bound and the
        // manager is active. Waiting for the hook-ready barrier closes the cold
        // start window in which the first F18 press could be swallowed or lost.
        controller.getVoiceRelay().start();
        if (!controller.getVoiceRelay().awaitReady(1_500)) {
            controller.getVoiceRelay().stop();
            voiceInputManager.stopVoiceInput();
            voiceRunning = false;
            updateVoiceButtonState();
            setVoiceStatus("error", "语音热键初始化失败");
            logger.error("VOICE_STARTUP_FAILED: low-level hook did not become ready");
            return;
        }
        logger.info("VOICE_INPUT_READY provider={} hook=true clipboard=true",
            voiceInputManager.getSpeechService().getProviderDisplayName());
    }

    /**
     * 停止语音服务
     */
    private void stopVoiceService() {
        voiceRunning = false;
        updateVoiceButtonState();
        setVoiceStatus("stopping", "语音关闭中");

        // 隐藏浮动通知窗口（不销毁，单例复用）
        if (floatingNotification != null) {
            floatingNotification.hide();
        }

        if (voiceInputManager != null) {
            controller.getVoiceRelay().stop();
            voiceInputManager.stopVoiceInput();
        }

        // 延迟更新状态
        new Thread(() -> {
            try {
                Thread.sleep(500);
                Platform.runLater(() -> {
                    setVoiceStatus("stopped", "语音未启动");
                    voiceResultPreview.setText("");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * 设置语音状态
     */
    private void setVoiceStatus(String status, String text) {
        if (voiceStatusLamp != null) {
            voiceStatusLamp.setStatus(status);
        }

        if (voiceStatusLabel != null) {
            voiceStatusLabel.setText(text);

            // 根据状态设置颜色
            String color = switch (status) {
                case "stopped", "idle" -> "#A7AFBA";           // 空闲状态 - 灰色
                case "starting", "stopping", "processing", "recognizing" -> "#F5A623";  // 处理/识别中 - 橙色
                case "recording" -> "#E74C3C";                 // 录音中 - 红色
                case "ready" -> "#2ECC71";                     // 就绪 - 绿色
                default -> "#E74C3C"; // error
            };
            voiceStatusLabel.setStyle("-fx-text-fill: " + color + ";");
        }
    }

    /**
     * 更新语音按钮状态
     */
    private void updateVoiceButtonState() {
        if (voiceRecordButton == null) return;

        if (voiceInputManager == null || !voiceInputManager.isEnabled()) {
            voiceRecordButton.setDisable(true);
            voiceRecordButton.setText("启动语音输入 (不可用)");
            setVoiceStatus("error", "语音服务未加载");
            return;
        }

        voiceRecordButton.setDisable(false);

        if (voiceRunning) {
            voiceRecordButton.getStyleClass().add("voice-recording");
            voiceRecordButton.setText("停止语音输入");
        } else {
            voiceRecordButton.getStyleClass().remove("voice-recording");
            voiceRecordButton.setText("启动语音输入");
        }
    }

    private VBox createStatusBox(
        javafx.beans.value.ObservableValue<Boolean> isPositive,
        javafx.beans.value.ObservableValue<String> title,
        javafx.beans.value.ObservableValue<String> detail
    ) {
        VBox box = new VBox(1);
        box.getStyleClass().add("status-box");
        box.setAlignment(Pos.CENTER_LEFT);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        Label dot = new Label();
        dot.getStyleClass().add("status-dot");
        dot.styleProperty().bind(Bindings.createStringBinding(
            () -> "-fx-background-color: " + (isPositive.getValue() ? "#30d158" : "#0a84ff") + ";",
            isPositive
        ));

        VBox text = new VBox(1);
        text.setAlignment(Pos.CENTER_LEFT);
        Label titleLabel = new Label();
        titleLabel.textProperty().bind(title);
        titleLabel.getStyleClass().add("status-label");

        Label detailLabel = new Label();
        detailLabel.textProperty().bind(detail);
        detailLabel.getStyleClass().add("status-detail");

        text.getChildren().addAll(titleLabel, detailLabel);
        row.getChildren().addAll(dot, text);
        box.getChildren().add(row);
        return box;
    }

    private void showDeviceInfoDialog() {
        Stage dialog = new Stage();
        dialog.initOwner(getScene().getWindow());
        dialog.setTitle("Hook 安装 & 分发工具");
        dialog.setWidth(550);
        dialog.setHeight(650);

        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);

        VBox content = new VBox(12);
        content.setPadding(new Insets(12));

        // 设备信息摘要
        VBox deviceCard = new VBox(8);
        deviceCard.getStyleClass().add("dialog-card");
        deviceCard.setPadding(new Insets(12));

        Label deviceTitle = new Label("设备信息");
        deviceTitle.getStyleClass().add("dialog-card-title");

        HBox deviceRow1 = new HBox(16);
        Label connStatus = new Label();
        connStatus.getStyleClass().add("dialog-text");
        connStatus.textProperty().bind(Bindings.createStringBinding(() ->
            "连接: " + (this.deviceStatus.isConnected() ? "已连接" : "未连接"),
            this.deviceStatus.isConnectedProperty()
        ));
        Label batteryStatus = new Label();
        batteryStatus.getStyleClass().add("dialog-text");
        batteryStatus.textProperty().bind(Bindings.createStringBinding(() ->
            "电量: " + this.deviceStatus.getBatteryLevel() + "%",
            this.deviceStatus.batteryLevelProperty()
        ));
        deviceRow1.getChildren().addAll(connStatus, batteryStatus);

        HBox deviceRow2 = new HBox(16);
        Label deviceName = new Label("设备名: " + (this.deviceStatus.getDeviceName() != null ? this.deviceStatus.getDeviceName() : "—"));
        deviceName.getStyleClass().add("dialog-text");
        Label switchState = new Label();
        switchState.getStyleClass().add("dialog-text");
        switchState.textProperty().bind(Bindings.createStringBinding(() ->
            "拨杆: " + this.deviceStatus.getSwitchTitle(),
            this.deviceStatus.switchStateProperty()
        ));
        deviceRow2.getChildren().addAll(deviceName, switchState);

        deviceCard.getChildren().addAll(deviceTitle, deviceRow1, deviceRow2);

        // 日志区域（提前创建以记录检测过程）
        logArea = new TextArea();
        logArea.getStyleClass().add("dialog-log-area");
        logArea.setEditable(false);
        logArea.setPrefHeight(150);
        logArea.setWrapText(true);
        logArea.setText("[系统] Hook 安装工具已启动\n");

        // 输出用户目录信息
        String homeDir = System.getProperty("user.home");
        addLog("[系统] 用户目录: " + homeDir);
        addLog("[系统] 操作系统: " + System.getProperty("os.name"));
        addLog("[系统] Java 版本: " + System.getProperty("java.version"));
        addLog("");

        // 检测并记录各 Hook 状态
        String[] hookNames = {"Claude", "Cursor", "Codex", "Kimi"};
        boolean[] hookInstalled = new boolean[4];

        for (int i = 0; i < hookNames.length; i++) {
            String name = hookNames[i];
            Path path = hookInstaller.getHookConfigPath(name);
            File file = path.toFile();

            // 使用完整的检查逻辑
            boolean installed = isHookInstalled(name);

            // 添加详细调试信息
            addLog("[检测] === " + name + " Hook ===");
            addLog("[检测] 检查路径: " + path);
            addLog("[检测] 文件存在: " + file.exists());

            if (file.exists()) {
                try {
                    String fileContent = new String(java.nio.file.Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
                    addLog("[检测] 文件大小: " + fileContent.length() + " 字符");

                    if ("Claude".equals(name)) {
                        addLog("[检测] 包含 hooks: " + fileContent.contains("\"hooks\""));
                        addLog("[检测] 包含 SessionStart: " + fileContent.contains("SessionStart"));
                    } else if ("Cursor".equals(name)) {
                        addLog("[检测] 包含 hooks: " + fileContent.contains("\"hooks\""));
                        addLog("[检测] 包含 sessionStart: " + fileContent.contains("sessionStart"));
                    } else if ("Codex".equals(name)) {
                        String home = System.getProperty("user.home");
                        Path sidecar = Paths.get(home, ".codex", HookInstaller.CODEX_SIDECAR_NAME);
                        addLog("[检测] sidecar 存在: " + sidecar.toFile().exists());
                        addLog("[检测] hooks.json 内容长度: " + fileContent.length());
                    } else if ("Kimi".equals(name)) {
                        addLog("[检测] 包含 BEGIN 标记: " + fileContent.contains(HookInstaller.KIMI_HOOK_BLOCK_START));
                        addLog("[检测] 包含 END 标记: " + fileContent.contains(HookInstaller.KIMI_HOOK_BLOCK_END));
                    }
                } catch (Exception e) {
                    addLog("[检测] 读取文件失败: " + e.getMessage());
                }
            }

            hookInstalled[i] = installed;
            addLog("[检测] 最终状态: " + (installed ? "已安装" : "未安装"));
            addLog("");
        }

        // Hook 安装卡片
        VBox claudeCard = createHookCard("Claude", hookInstalled[0]);
        VBox cursorCard = createHookCard("Cursor", hookInstalled[1]);
        VBox codexCard = createHookCard("Codex", hookInstalled[2]);
        VBox kimiCard = createHookCard("Kimi", hookInstalled[3]);

        // 日志卡片
        VBox logCard = new VBox(8);
        logCard.getStyleClass().add("dialog-card");
        logCard.setPadding(new Insets(12));

        Label logTitle = new Label("日志");
        logTitle.getStyleClass().add("dialog-log-title");
        logCard.getChildren().addAll(logTitle, logArea);

        // 操作按钮
        HBox actionButtons = new HBox(10);
        Button connectBtn = new Button("连接设备");
        connectBtn.getStyleClass().add("button-connect");
        connectBtn.disableProperty().bind(this.deviceStatus.isConnectedProperty());
        connectBtn.setOnAction(event -> controller.userConnect());

        Button disconnectBtn = new Button("断开连接");
        disconnectBtn.getStyleClass().add("button-disconnect");
        disconnectBtn.disableProperty().bind(this.deviceStatus.isConnectedProperty().not());
        disconnectBtn.setOnAction(event -> controller.userDisconnect());

        Button clearLogBtn = new Button("清空日志");
        clearLogBtn.setOnAction(event -> logArea.setText("[系统] 日志已清空\n"));

        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(event -> dialog.close());

        actionButtons.getChildren().addAll(connectBtn, disconnectBtn, clearLogBtn, closeBtn);
        actionButtons.setAlignment(Pos.CENTER_RIGHT);

        content.getChildren().addAll(deviceCard, claudeCard, cursorCard, codexCard, kimiCard, logCard, actionButtons);
        scrollPane.setContent(content);

        Scene scene = new Scene(scrollPane);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private VBox createHookCard(String hookName, boolean isInstalled) {
        VBox card = new VBox(8);
        card.getStyleClass().add("dialog-card");
        card.setPadding(new Insets(12));

        HBox titleRow = new HBox();
        Label title = new Label(hookName + " Hook");
        title.getStyleClass().add("dialog-card-title");
        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);
        titleRow.getChildren().addAll(title, spacer1);

        HBox statusRow = new HBox(8);
        Label statusLabel = new Label("安装状态:");
        statusLabel.getStyleClass().add("dialog-status-label");

        Label statusValue = new Label(isInstalled ? "已安装" : "未安装");
        if (isInstalled) {
            statusValue.getStyleClass().add("dialog-status-installed");
        } else {
            statusValue.getStyleClass().add("dialog-status-uninstalled");
        }

        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);

        Button installBtn = new Button("安装");
        installBtn.getStyleClass().add("button-install");
        installBtn.setOnAction(event -> {
            installHook(hookName);
            statusValue.setText("已安装");
            statusValue.getStyleClass().remove("dialog-status-uninstalled");
            statusValue.getStyleClass().add("dialog-status-installed");
        });

        Button uninstallBtn = new Button("卸载");
        uninstallBtn.getStyleClass().add("button-uninstall");
        uninstallBtn.setOnAction(event -> {
            uninstallHook(hookName);
            statusValue.setText("未安装");
            statusValue.getStyleClass().remove("dialog-status-installed");
            statusValue.getStyleClass().add("dialog-status-uninstalled");
        });

        statusRow.getChildren().addAll(statusLabel, statusValue, spacer2, installBtn, uninstallBtn);

        card.getChildren().addAll(titleRow, statusRow);
        return card;
    }

    // ==================== Hook 管理（委托给 HookInstaller） ====================

    private boolean isHookInstalled(String hookName) {
        return hookInstaller.isInstalled(hookName);
    }

    private void installHook(String hookName) {
        hookInstaller.install(hookName);
    }

    private void uninstallHook(String hookName) {
        addLog("[卸载] 开始卸载 " + hookName + " Hook...");
        hookInstaller.uninstall(hookName);
    }

    private void addLog(String message) {
        if (logArea != null) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + timestamp + "] " + message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    private void showVersionDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("版本信息");
        alert.setHeaderText(null);

        String version = getVersion();
        alert.setContentText("版本号: " + version);

        alert.showAndWait();
    }

    private String getVersion() {
        String version = System.getProperty("app.version");
        if (version == null || version.isEmpty()) {
            version = "unknown";
        }
        return version;
    }

    /**
     * 语音状态指示灯组件
     */
    private class VoiceStatusLamp extends Canvas {
        private String status = "stopped";
        private double angle = 0;
        private AnimationTimer timer;
        private boolean isTimerRunning = false;

        public VoiceStatusLamp() {
            super(16, 16);
            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    angle = (angle + 30) % 360;
                    draw();
                }
            };
        }

        public void setStatus(String status) {
            this.status = status != null ? status : "stopped";
            if (status.equals("starting") || status.equals("stopping") || status.equals("processing")) {
                if (!isTimerRunning) {
                    timer.start();
                    isTimerRunning = true;
                }
            } else {
                timer.stop();
                isTimerRunning = false;
                angle = 0;
            }
            draw();
        }

        private void draw() {
            GraphicsContext gc = getGraphicsContext2D();
            gc.clearRect(0, 0, getWidth(), getHeight());

            if (status.equals("stopped")) {
                // 灰色空心圆
                gc.setStroke(javafx.scene.paint.Color.web("#8A9099"));
                gc.setLineWidth(1.6);
                gc.strokeOval(2.0, 2.0, getWidth() - 4, getHeight() - 4);
            } else if (status.equals("starting") || status.equals("stopping") || status.equals("processing")) {
                // 旋转动画
                gc.setStroke(javafx.scene.paint.Color.web("#5C6470"));
                gc.setLineWidth(1.6);
                gc.strokeOval(2.0, 2.0, getWidth() - 4, getHeight() - 4);

                gc.setStroke(javafx.scene.paint.Color.web("#F5A623"));
                gc.setLineWidth(2.2);
                gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);

                double startAngle = -angle * Math.PI / 180;
                double arcLength = -120 * Math.PI / 180;
                gc.strokeArc(2.0, 2.0, getWidth() - 4, getHeight() - 4, startAngle, arcLength, javafx.scene.shape.ArcType.OPEN);
            } else if (status.equals("ready")) {
                // 绿色实心圆
                gc.setFill(javafx.scene.paint.Color.web("#2ECC71"));
                gc.fillOval(2.0, 2.0, getWidth() - 4, getHeight() - 4);
            } else {
                // 红色实心圆（error）
                gc.setFill(javafx.scene.paint.Color.web("#E74C3C"));
                gc.fillOval(2.0, 2.0, getWidth() - 4, getHeight() - 4);
            }
        }
    }
}
