package com.example.ahakey.service;

import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.protocol.AhaKeyProtocol;
import com.example.ahakey.protocol.AhaKeyResponseParser;
import com.example.ahakey.util.OLEDFrameEncoder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class OledUploadService {
    private static final int DEVICE_MODE_COUNT = 3;
    private static final int FALLBACK_TOTAL_FRAME_SLOTS = AhaKeyProtocol.OLED_MAX_FRAMES;
    private static final int FACTORY_RESERVED_FRAME_SLOTS = 10;

    public record UploadProgress(int completedFrames, int totalFrames, String detail) {
    }

    public record UploadPlan(int startIndex, int frameCount, int usableCapacity, int totalCapacity) {
        public long encodedBytes() {
            return (long) frameCount * AhaKeyProtocol.OLED_FRAME_BYTES;
        }
    }

    private OledUploadService() {
    }

    public static int fallbackTotalFrameSlots() {
        return FALLBACK_TOTAL_FRAME_SLOTS;
    }

    public static int factoryReservedFrameSlots() {
        return FACTORY_RESERVED_FRAME_SLOTS;
    }

    /**
     * Returns the largest possible single upload in the user-managed picture area.
     *
     * <p>The method name is retained for the existing controller. Capacity is no
     * longer divided into four fixed mode partitions.</p>
     */
    public static int perModeCapacity(int totalCapacity) {
        return Math.max(0, totalCapacity - FACTORY_RESERVED_FRAME_SLOTS);
    }

    public static UploadPlan validateUploadPlan(BleManager ble, ModeSlot mode, int frameCount) throws Exception {
        if (frameCount <= 0) {
            throw new IllegalStateException("没有可上传的 OLED 帧。");
        }
        validateDeviceMode(mode);

        List<AhaKeyResponseParser.PictureState> states = readAllPictureStates(ble);
        int totalCapacity = resolveDeviceCapacity(states);
        if (totalCapacity <= FACTORY_RESERVED_FRAME_SLOTS) {
            throw new IllegalStateException("设备 Flash 图片分区容量异常，已取消上传。");
        }

        int usableCapacity = perModeCapacity(totalCapacity);
        if (frameCount > usableCapacity) {
            throw new IllegalStateException(
                "当前 GIF 有 " + frameCount + " 帧，超过设备可用上限 " + usableCapacity + " 帧。"
            );
        }

        int startIndex = resolveDynamicStartIndex(mode.getIndex(), frameCount, totalCapacity, states);
        int endIndexExclusive = startIndex + frameCount;
        if (startIndex < FACTORY_RESERVED_FRAME_SLOTS || endIndexExclusive > totalCapacity) {
            throw new IllegalStateException("上传内容超过设备 Flash 可用空间，已取消上传。");
        }

        long encodedBytes = (long) frameCount * AhaKeyProtocol.OLED_FRAME_BYTES;
        long slotBytes = (long) frameCount * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
        long usableBytes = (long) usableCapacity * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
        long endAddressExclusive = (long) endIndexExclusive * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
        long flashBytes = (long) totalCapacity * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
        if (encodedBytes <= 0 || slotBytes > usableBytes || endAddressExclusive > flashBytes) {
            throw new IllegalStateException("上传内容超过设备 Flash 可用空间。");
        }
        return new UploadPlan(startIndex, frameCount, usableCapacity, totalCapacity);
    }

    private static void validateDeviceMode(ModeSlot mode) {
        if (mode == null || mode.getIndex() < 0 || mode.getIndex() >= DEVICE_MODE_COUNT) {
            throw new IllegalArgumentException("当前固件只支持 Mode 1/2/3 的 OLED 配置。");
        }
    }

    private static List<AhaKeyResponseParser.PictureState> readAllPictureStates(BleManager ble) throws Exception {
        List<AhaKeyResponseParser.PictureState> states = new ArrayList<>(DEVICE_MODE_COUNT);
        for (int mode = 0; mode < DEVICE_MODE_COUNT; mode++) {
            AhaKeyResponseParser.PictureState state = ble.readPictureState(mode);
            if (state == null || state.mode() != mode) {
                throw new IllegalStateException("无法读取 Mode " + (mode + 1) + " 的 OLED 分区状态。");
            }
            states.add(state);
        }
        return states;
    }

    private static int resolveDeviceCapacity(List<AhaKeyResponseParser.PictureState> states) {
        int capacity = Integer.MAX_VALUE;
        for (AhaKeyResponseParser.PictureState state : states) {
            if (state.allModeMaxPic() <= 0) {
                throw new IllegalStateException("设备未返回有效的 OLED Flash 容量。");
            }
            // The three replies should agree. The smallest mixed snapshot is the
            // only safe capacity if the bridge reconnects between queries.
            capacity = Math.min(capacity, state.allModeMaxPic());
        }
        return capacity;
    }

    static int resolveDynamicStartIndex(
        int targetMode,
        int frameCount,
        int totalCapacity,
        List<AhaKeyResponseParser.PictureState> states
    ) {
        AhaKeyResponseParser.PictureState targetState = states.get(targetMode);
        List<FrameRegion> occupied = new ArrayList<>();
        occupied.add(new FrameRegion(0, FACTORY_RESERVED_FRAME_SLOTS));

        for (AhaKeyResponseParser.PictureState state : states) {
            validatePictureRegion(state, totalCapacity);
            if (state.mode() != targetMode && state.picLength() > 0) {
                occupied.add(new FrameRegion(state.startIndex(), state.startIndex() + state.picLength()));
            }
        }
        List<FrameRegion> merged = mergeRegions(occupied);

        if (targetState.picLength() > 0 &&
            canPlace(targetState.startIndex(), frameCount, totalCapacity, merged)) {
            return targetState.startIndex();
        }

        int cursor = FACTORY_RESERVED_FRAME_SLOTS;
        for (FrameRegion region : merged) {
            if (region.endExclusive() <= cursor) {
                continue;
            }
            if ((long) cursor + frameCount <= region.start()) {
                return cursor;
            }
            cursor = Math.max(cursor, region.endExclusive());
        }
        if ((long) cursor + frameCount <= totalCapacity) {
            return cursor;
        }
        throw new IllegalStateException(
            "设备 OLED Flash 没有足够的连续空间（需要 " + frameCount + " 帧）。"
        );
    }

    private static void validatePictureRegion(AhaKeyResponseParser.PictureState state, int totalCapacity) {
        if (state.startIndex() < 0 || state.picLength() < 0 ||
            (long) state.startIndex() + state.picLength() > totalCapacity) {
            throw new IllegalStateException("设备返回了无效的 OLED 分区信息：Mode " + (state.mode() + 1));
        }
    }

    private static boolean canPlace(
        int start,
        int frameCount,
        int totalCapacity,
        List<FrameRegion> occupied
    ) {
        if (start < FACTORY_RESERVED_FRAME_SLOTS || (long) start + frameCount > totalCapacity) {
            return false;
        }
        int endExclusive = start + frameCount;
        for (FrameRegion region : occupied) {
            if (start < region.endExclusive() && endExclusive > region.start()) {
                return false;
            }
        }
        return true;
    }

    private static List<FrameRegion> mergeRegions(List<FrameRegion> regions) {
        List<FrameRegion> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(FrameRegion::start));
        List<FrameRegion> merged = new ArrayList<>();
        for (FrameRegion region : sorted) {
            if (merged.isEmpty()) {
                merged.add(region);
                continue;
            }
            FrameRegion last = merged.get(merged.size() - 1);
            if (region.start() <= last.endExclusive()) {
                merged.set(
                    merged.size() - 1,
                    new FrameRegion(last.start(), Math.max(last.endExclusive(), region.endExclusive()))
                );
            } else {
                merged.add(region);
            }
        }
        return merged;
    }

    private record FrameRegion(int start, int endExclusive) {
    }

    public static void uploadGif(
        BleManager ble,
        ModeSlot mode,
        Path gifPath,
        int fps,
        Consumer<UploadProgress> onProgress,
        Consumer<String> onComplete,
        Consumer<String> onError
    ) {
        new Thread(() -> {
            try {
                int frameCount = OLEDFrameEncoder.frameCount(gifPath);
                UploadPlan plan = validateUploadPlan(ble, mode, frameCount);
                List<OLEDFrameEncoder.EncodedFrame> frames = OLEDFrameEncoder.framesFromGif(gifPath, plan.frameCount());
                int startIndex = plan.startIndex();
                int delayMs = Math.max(1, 1000 / Math.max(1, fps));
                int total = frames.size();

                for (int i = 0; i < total; i++) {
                    long address = (long) (startIndex + i) * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
                    if (onProgress != null) {
                        onProgress.accept(new UploadProgress(i, total, "写入帧 " + (i + 1) + "/" + total));
                    }
                    ble.writeLargeData(address, frames.get(i).rgb565);
                    Thread.sleep(100);
                }

                ble.sendCommandExpecting(
                    AhaKeyProtocol.updatePicture(mode.getIndex(), startIndex, total, delayMs),
                    AhaKeyProtocol.CMD_UPDATE_PIC
                );

                if (onComplete != null) {
                    onComplete.accept(mode.getTitle() + " OLED GIF 上传完成：" + total + " 帧");
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (onError != null) {
                    onError.accept(errorMsg);
                }
            }
        }, "oled-upload").start();
    }

    public static void uploadStaticImage(
        BleManager ble,
        ModeSlot mode,
        Path imagePath,
        Consumer<UploadProgress> onProgress,
        Consumer<String> onComplete,
        Consumer<String> onError
    ) {
        new Thread(() -> {
            try {
                UploadPlan plan = validateUploadPlan(ble, mode, 1);
                OLEDFrameEncoder.EncodedFrame frame = OLEDFrameEncoder.frameFromSingleImage(imagePath);
                int startIndex = plan.startIndex();

                long address = (long) startIndex * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE;
                if (onProgress != null) {
                    onProgress.accept(new UploadProgress(0, 1, "写入静态图片"));
                }
                ble.writeLargeData(address, frame.rgb565);

                ble.sendCommandExpecting(
                    AhaKeyProtocol.updatePicture(mode.getIndex(), startIndex, 1, 0),
                    AhaKeyProtocol.CMD_UPDATE_PIC
                );

                if (onComplete != null) {
                    onComplete.accept(mode.getTitle() + " OLED 图片上传完成");
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (onError != null) {
                    onError.accept(errorMsg);
                }
            }
        }, "oled-upload").start();
    }

    public static void previewGif(
        BleManager ble,
        Path gifPath,
        int fps,
        Consumer<String> onComplete,
        Consumer<String> onError
    ) {
        new Thread(() -> {
            try {
                int frameCount = OLEDFrameEncoder.frameCount(gifPath);
                UploadPlan plan = validateUploadPlan(ble, ModeSlot.MODE0, frameCount);
                List<OLEDFrameEncoder.EncodedFrame> frames = OLEDFrameEncoder.framesFromGif(gifPath, plan.frameCount());
                int delayMs = Math.max(1, 1000 / Math.max(1, fps));
                int total = frames.size();
                int startIndex = plan.startIndex();

                for (int i = 0; i < total; i++) {
                    ble.writeLargeData((long) (startIndex + i) * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE, frames.get(i).rgb565);
                }

                ble.sendCommandExpecting(
                    AhaKeyProtocol.updatePicture(0, startIndex, total, delayMs),
                    AhaKeyProtocol.CMD_UPDATE_PIC
                );

                if (onComplete != null) {
                    onComplete.accept("OLED 预览已发送：" + total + " 帧");
                }
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (onError != null) {
                    onError.accept("预览失败：" + errorMsg);
                }
            }
        }, "oled-preview").start();
    }
}
