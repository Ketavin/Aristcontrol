package com.example.ahakey.view;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.ArcType;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * 浮动语音通知窗口
 * 在桌面上显示语音状态提示，始终在最顶层
 * 参考 ahakeyconfig-win-python 的 VoiceHud 样式
 * 单例模式，确保只有一个实例
 */
public class FloatingVoiceNotification {

    private static FloatingVoiceNotification instance;
    private static final Object lock = new Object();

    private Stage stage;
    private Canvas indicator;
    private Text statusText;
    private AnimationTimer spinTimer;
    private boolean isTimerRunning = false;
    private double angle = 0;
    private String currentStatus = "idle";

    /**
     * 状态配置
     */
    private static class StatusConfig {
        String text;
        Color spinnerColor;
        boolean isSpinner;

        StatusConfig(String text, Color spinnerColor, boolean isSpinner) {
            this.text = text;
            this.spinnerColor = spinnerColor;
            this.isSpinner = isSpinner;
        }
    }

    /**
     * 获取单例实例
     */
    public static FloatingVoiceNotification getInstance() {
        synchronized (lock) {
            if (instance == null) {
                instance = new FloatingVoiceNotification();
            }
            return instance;
        }
    }

    /**
     * 私有化构造函数
     */
    private FloatingVoiceNotification() {
        init();
    }

    /**
     * 销毁单例（用于重置）
     */
    public static void destroy() {
        synchronized (lock) {
            if (instance != null) {
                instance.close();
                instance = null;
            }
        }
    }

    private void init() {
        stage = new Stage();
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.initModality(javafx.stage.Modality.NONE);
        stage.initOwner(null);

        HBox content = new HBox(12);
        content.setStyle(
            "-fx-background-color: rgba(20, 24, 30, 220);" +
            "-fx-padding: 10 18 10 18;" +
            "-fx-border-radius: 22px;" +
            "-fx-background-radius: 22px;" +
            "-fx-border-color: rgba(255, 255, 255, 30);" +
            "-fx-border-width: 1px;"
        );
        content.setFocusTraversable(false);
        content.setMouseTransparent(true);
        content.setMinHeight(44);

        indicator = new Canvas(18, 18);

        statusText = new Text();
        statusText.setFont(Font.font("Microsoft YaHei", FontWeight.SEMI_BOLD, 14));
        statusText.setFill(Color.web("#F4F7FB"));

        content.getChildren().addAll(indicator, statusText);

        Scene scene = new Scene(content);
        scene.setFill(Color.TRANSPARENT);
        scene.getRoot().setFocusTraversable(false);

        stage.setScene(scene);

        startSpinAnimation();
    }

    public void updateStatus(String status, String message) {
        Platform.runLater(() -> {
            currentStatus = status;

            StatusConfig config = getStatusConfig(status, message);
            statusText.setText(config.text);

            drawIndicator(config.spinnerColor, config.isSpinner);

            stage.sizeToScene();

            if (!"idle".equals(status) && !"stopped".equals(status) && !"ready".equals(status)) {
                show();
            } else {
                hide();
            }

            if ("error".equals(status)) {
                PauseTransition delay = new PauseTransition(Duration.seconds(3));
                delay.setOnFinished(event -> {
                    if ("error".equals(currentStatus)) {
                        hide();
                    }
                });
                delay.play();
            }
        });
    }

    private StatusConfig getStatusConfig(String status, String message) {
        return switch (status) {
            case "recording" -> new StatusConfig(
                message != null && !message.isBlank() ? message : "语音输入中",
                Color.web("#E74C3C"),
                false
            );
            case "recognizing" -> new StatusConfig(
                message != null && !message.isBlank() ? message : "识别中",
                Color.web("#F5A623"),
                true
            );
            case "processing" -> new StatusConfig("处理中", Color.web("#F5A623"), true);
            case "error" -> new StatusConfig(
                message != null && !message.isBlank() ? message : "识别失败，请重试",
                Color.web("#E74C3C"),
                false
            );
            case "ready" -> new StatusConfig("语音就绪", Color.web("#2ECC71"), false);
            case "starting" -> new StatusConfig("启动中", Color.web("#F5A623"), true);
            case "stopping" -> new StatusConfig("关闭中", Color.web("#F5A623"), true);
            default -> new StatusConfig(message != null ? message : "空闲", Color.web("#5C6470"), false);
        };
    }

    private void drawIndicator(Color spinnerColor, boolean isSpinner) {
        GraphicsContext gc = indicator.getGraphicsContext2D();
        gc.clearRect(0, 0, indicator.getWidth(), indicator.getHeight());

        double centerX = indicator.getWidth() / 2;
        double centerY = indicator.getHeight() / 2;
        double radius = 7;

        if (isSpinner) {
            gc.setStroke(Color.web("#5C6470"));
            gc.setLineWidth(1.8);
            gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);

            gc.setStroke(spinnerColor);
            gc.setLineWidth(2.4);
            gc.strokeArc(centerX - radius, centerY - radius, radius * 2, radius * 2,
                         -angle, -120, ArcType.OPEN);
        } else {
            gc.setFill(spinnerColor);
            gc.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        }
    }

    private void startSpinAnimation() {
        spinTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isTimerRunning) return;

                angle = (angle + 30) % 360;

                StatusConfig config = getStatusConfig(currentStatus, statusText.getText());
                if (config.isSpinner) {
                    Platform.runLater(() -> {
                        drawIndicator(config.spinnerColor, config.isSpinner);
                    });
                }
            }
        };
        isTimerRunning = true;
        spinTimer.start();
    }

    public void show() {
        Platform.runLater(() -> {
            if (!stage.isShowing()) {
                double screenWidth = javafx.stage.Screen.getPrimary().getBounds().getWidth();
                double screenHeight = javafx.stage.Screen.getPrimary().getBounds().getHeight();
                stage.setX((screenWidth - stage.getWidth()) / 2);
                stage.setY(screenHeight - 84);

                try {
                    com.sun.jna.platform.win32.WinDef.HWND foregroundWindow =
                        com.sun.jna.platform.win32.User32.INSTANCE.GetForegroundWindow();

                    stage.show();

                    if (foregroundWindow != null) {
                        com.sun.jna.platform.win32.User32.INSTANCE.SetForegroundWindow(foregroundWindow);
                    }
                } catch (Exception e) {
                    stage.show();
                }
            }
        });
    }

    public void hide() {
        Platform.runLater(() -> {
            if (stage.isShowing()) {
                stage.hide();
            }
        });
    }

    public void close() {
        isTimerRunning = false;
        if (spinTimer != null) {
            spinTimer.stop();
        }
        if (stage != null) {
            stage.close();
        }
    }

    private abstract class AnimationTimer {
        public abstract void handle(long now);

        public void start() {
            FloatingVoiceNotification.this.isTimerRunning = true;
            new Thread(() -> {
                while (FloatingVoiceNotification.this.isTimerRunning) {
                    handle(System.currentTimeMillis());
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }).start();
        }

        public void stop() {
            FloatingVoiceNotification.this.isTimerRunning = false;
        }
    }
}
