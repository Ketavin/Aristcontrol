package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.model.ModeSlot;
import com.example.ahakey.protocol.AhaKeyProtocol;
import com.example.ahakey.protocol.AhaKeyResponseParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises three-mode dynamic OLED allocation without a physical keyboard. */
public final class OledUploadServiceAllocationSmokeTest {
    private OledUploadServiceAllocationSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        reservesFactorySlotsAndQueriesAllModes();
        reusesTargetStartWhenItCanGrowSafely();
        firstFitAvoidsOtherModesAndAllowsMoreThanSeventyFrames();
        previewUsesTheSameSafeAllocator();
        System.out.println("OLED dynamic allocation smoke test passed");
    }

    private static void reservesFactorySlotsAndQueriesAllModes() throws Exception {
        FakeBleManager ble = manager(
            state(0, 0, 0, 100),
            state(1, 0, 0, 100),
            state(2, 0, 0, 100)
        );

        OledUploadService.UploadPlan plan =
            OledUploadService.validateUploadPlan(ble, ModeSlot.MODE0, 5);

        require(plan.startIndex() == 10, "allocation must preserve factory slots [0,10)");
        require(plan.usableCapacity() == 90, "capacity must not be divided into four modes");
        require(ble.queriedModes.equals(List.of(0, 1, 2)), "allocator must query modes 0, 1 and 2");
    }

    private static void reusesTargetStartWhenItCanGrowSafely() throws Exception {
        FakeBleManager ble = manager(
            state(0, 10, 20, 120),
            state(1, 40, 5, 120),
            state(2, 80, 10, 120)
        );

        OledUploadService.UploadPlan plan =
            OledUploadService.validateUploadPlan(ble, ModeSlot.MODE1, 25);

        require(plan.startIndex() == 40, "target allocation should reuse its old start when expansion is safe");
    }

    private static void firstFitAvoidsOtherModesAndAllowsMoreThanSeventyFrames() throws Exception {
        FakeBleManager fragmented = manager(
            state(0, 10, 20, 120),
            state(1, 70, 5, 120),
            state(2, 60, 30, 120)
        );
        OledUploadService.UploadPlan gapPlan =
            OledUploadService.validateUploadPlan(fragmented, ModeSlot.MODE1, 20);
        require(gapPlan.startIndex() == 30, "first-fit should use the first safe gap between other modes");

        FakeBleManager largeDevice = manager(
            state(0, 10, 70, 292),
            state(1, 80, 70, 292),
            state(2, 0, 0, 292)
        );
        OledUploadService.UploadPlan largePlan =
            OledUploadService.validateUploadPlan(largeDevice, ModeSlot.MODE2, 100);
        require(largePlan.startIndex() == 150, "three-mode dynamic allocation should permit a 100-frame free region");
        require(largePlan.totalCapacity() == 292, "device-reported total capacity must be authoritative");
    }

    private static void previewUsesTheSameSafeAllocator() throws Exception {
        FakeBleManager ble = manager(
            state(0, 0, 0, 100),
            state(1, 10, 10, 100),
            state(2, 30, 10, 100)
        );
        Path gif = Files.createTempFile("ahakey-oled-preview-", ".gif");
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            require(ImageIO.write(image, "gif", gif.toFile()), "test runtime must support GIF encoding");

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<String> error = new AtomicReference<>();
            OledUploadService.previewGif(
                ble,
                gif,
                10,
                ignored -> finished.countDown(),
                message -> {
                    error.set(message);
                    finished.countDown();
                }
            );

            require(finished.await(5, TimeUnit.SECONDS), "preview did not finish");
            require(error.get() == null, "preview failed: " + error.get());
            require(
                ble.largeWriteAddresses.equals(List.of(20L * AhaKeyProtocol.OLED_FRAME_SLOT_SIZE)),
                "preview must first-fit around existing allocations instead of overwriting slot 10"
            );
            require(ble.lastExpectedCommand == AhaKeyProtocol.CMD_UPDATE_PIC, "preview must commit picture metadata");
            require(readLittleEndian16(ble.lastCommand, 4) == 20, "preview metadata must use the allocated start slot");
        } finally {
            Files.deleteIfExists(gif);
        }
    }

    private static int readLittleEndian16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static FakeBleManager manager(AhaKeyResponseParser.PictureState... states) {
        return new FakeBleManager(Arrays.asList(states));
    }

    private static AhaKeyResponseParser.PictureState state(int mode, int start, int length, int capacity) {
        return new AhaKeyResponseParser.PictureState(mode, start, length, 100, capacity);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeBleManager extends BleManager {
        private static final BleCallback NO_OP_CALLBACK = new BleCallback() {
            @Override public void onConnected() { }
            @Override public void onDisconnected() { }
            @Override public void onStatusReceived(DeviceStatus status) { }
            @Override public void onError(String message) { }
        };

        private final List<AhaKeyResponseParser.PictureState> states;
        private final List<Integer> queriedModes = new ArrayList<>();
        private final List<Long> largeWriteAddresses = new ArrayList<>();
        private byte[] lastCommand;
        private byte lastExpectedCommand;

        private FakeBleManager(List<AhaKeyResponseParser.PictureState> states) {
            super(NO_OP_CALLBACK);
            this.states = states;
        }

        @Override
        public AhaKeyResponseParser.PictureState readPictureState(int mode) {
            queriedModes.add(mode);
            return states.get(mode);
        }

        @Override
        public void writeLargeData(long address, byte[] data) {
            largeWriteAddresses.add(address);
        }

        @Override
        public void sendCommandExpecting(byte[] command, byte expectedCmd) {
            lastCommand = command.clone();
            lastExpectedCommand = expectedCmd;
        }
    }
}
