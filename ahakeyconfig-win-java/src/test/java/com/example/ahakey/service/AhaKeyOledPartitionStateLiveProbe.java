package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.protocol.AhaKeyResponseParser;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in, read-only probe for all three OLED partition-state responses. */
public final class AhaKeyOledPartitionStateLiveProbe {
    private AhaKeyOledPartitionStateLiveProbe() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<String> lastError = new AtomicReference<>();
        BleManager manager = new BleManager(new BleManager.BleCallback() {
            @Override
            public void onConnected() {
                connected.countDown();
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onStatusReceived(DeviceStatus status) {
                if (status.isConnected()) {
                    connected.countDown();
                }
            }

            @Override
            public void onError(String message) {
                lastError.set(message);
            }
        });

        manager.connect();
        try {
            require(
                connected.await(20, TimeUnit.SECONDS),
                "BLE bridge/device did not connect: " + lastError.get()
            );

            for (int mode = 0; mode < 3; mode++) {
                AhaKeyResponseParser.PictureState state = manager.readPictureState(mode);
                require(state != null, "OLED " + (mode + 1) + " state was not parsed");
                require(state.mode() == mode, "OLED " + (mode + 1) + " returned the wrong mode");
                require(state.allModeMaxPic() > 0, "OLED capacity must be positive");
                System.out.printf(
                    "OLED %d: start=%d, frames=%d, interval=%dms, capacity=%d%n",
                    mode + 1,
                    state.startIndex(),
                    state.picLength(),
                    state.frameInterval(),
                    state.allModeMaxPic()
                );
            }
        } finally {
            manager.disconnect();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
