package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in live smoke test against an already running BLE bridge and a connected 507C. */
public final class AhaKeyBleLightRoundTripSmokeTest {

    private AhaKeyBleLightRoundTripSmokeTest() {
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
            require(connected.await(8, TimeUnit.SECONDS),
                "BLE bridge/device did not connect: " + lastError.get());

            BleManager.LightStatus status = manager.applyLightBrightnessAndVerify(
                100,
                (byte) 3,
                3000
            );
            require(status.lightMode() == 3, "live light mode readback did not match");
            require(status.brightness() == 100, "live brightness readback did not match");

            System.out.println(
                "AhaKey BLE light round-trip passed: effect=" + status.lightMode()
                    + ", brightness=" + status.brightness()
            );
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
