package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.protocol.AhaKeyProtocol;

import java.util.Arrays;

/** Smoke checks for the 507C light status payload and light-control frames. */
public final class AhaKeyProtocolLightStatusSmokeTest {

    private AhaKeyProtocolLightStatusSmokeTest() {
    }

    public static void main(String[] args) {
        byte[] payload = new byte[] {78, 50, 1, 0, 2, 3, 0, 100};
        DeviceStatus status = AhaKeyProtocol.parseDeviceStatusPayload(payload);
        require(status != null, "device status payload was not parsed");
        require(status.getBatteryLevel() == 78, "battery was not parsed");
        require(status.getSignal() == 50, "signal was not parsed");
        require(status.getFirmwareMain() == 1 && status.getFirmwareSub() == 0,
            "firmware version was not parsed");
        require(status.getWorkMode() == 2, "work mode was not parsed");
        require(status.getLightMode() == 3, "light mode was not parsed");
        require(status.getSwitchState() == 0, "switch state was not parsed");
        require(status.getLightBrightness() == 100, "light brightness was not parsed");

        DeviceStatus reservedBrightness = AhaKeyProtocol.parseDeviceStatusPayload(
            new byte[] {78, 50, 1, 0, 2, 1, 0, 0}
        );
        require(reservedBrightness != null && reservedBrightness.getLightBrightness() == -1,
            "reserved brightness byte must remain unknown");

        require(Arrays.equals(
            AhaKeyProtocol.setLightBrightness(100),
            new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0x85, 100, (byte) 0xCC, (byte) 0xDD}
        ), "brightness command frame changed");
        require(Arrays.equals(
            AhaKeyProtocol.setLightEffect((byte) 3),
            new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0x91, 3, (byte) 0xCC, (byte) 0xDD}
        ), "effect command frame changed");

        System.out.println("AhaKey light status smoke test passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
