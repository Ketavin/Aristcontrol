package com.example.ahakey.service;

import com.example.ahakey.model.DeviceStatus;
import com.example.ahakey.protocol.BleTcpPacket;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Proves that cached device info cannot satisfy a permission-time live refresh. */
public final class BleLiveStatusFreshnessSmokeTest {
    private BleLiveStatusFreshnessSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            AtomicInteger liveQueries = new AtomicInteger();
            AtomicReference<byte[]> firstRequestId = new AtomicReference<>();
            Thread fakeBridge = new Thread(
                () -> serve(server, liveQueries, firstRequestId),
                "fake-ble-bridge"
            );
            fakeBridge.setDaemon(true);
            fakeBridge.start();

            CountDownLatch deviceConnected = new CountDownLatch(1);
            BleManager manager = new BleManager("127.0.0.1", server.getLocalPort(), new BleManager.BleCallback() {
                @Override public void onConnected() { deviceConnected.countDown(); }
                @Override public void onDisconnected() { }
                @Override public void onStatusReceived(DeviceStatus status) { }
                @Override public void onError(String message) { }
            });

            manager.connect();
            require(deviceConnected.await(2, TimeUnit.SECONDS), "target device did not become ready");

            require(manager.queryStatusAndWait(500), "fresh live response should satisfy refresh");
            require(manager.getCachedStatus().getSwitchState() == 1,
                "fresh manual switch state should win over old auto cache");

            require(!manager.queryStatusAndWait(180),
                "cached DeviceInfoResp must not satisfy a missing live response");
            manager.disconnect();
        }
        System.out.println("BLE live status freshness smoke test passed");
    }

    private static void serve(
        ServerSocket server,
        AtomicInteger liveQueries,
        AtomicReference<byte[]> firstRequestId
    ) {
        try (Socket socket = server.accept()) {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            byte[] header = new byte[3];
            while (readFully(input, header)) {
                int length = (header[1] & 0xFF) | ((header[2] & 0xFF) << 8);
                byte[] body = new byte[length];
                if (!readFully(input, body)) {
                    return;
                }
                byte type = header[0];
                if (type == BleTcpPacket.QUERY_BLE_STATUS) {
                    write(output, BleTcpPacket.BLE_STATUS_RESP, targetStatus());
                } else if (type == BleTcpPacket.QUERY_DEVICE_INFO) {
                    write(output, BleTcpPacket.DEVICE_INFO_RESP, deviceInfo(0));
                } else if (type == BleTcpPacket.QUERY_LIVE_DEVICE_STATUS) {
                    int queryNumber = liveQueries.incrementAndGet();
                    // An old cache response is intentionally sent first on every query.
                    write(output, BleTcpPacket.DEVICE_INFO_RESP, deviceInfo(0));
                    if (queryNumber == 1) {
                        firstRequestId.set(body.clone());
                        Thread.sleep(20);
                        write(output, BleTcpPacket.LIVE_DEVICE_STATUS_RESP,
                            liveResponse(body, deviceInfo(1)));
                    } else {
                        // Simulate a late response from the previous request. Its nonce must
                        // not satisfy the current permission refresh.
                        write(output, BleTcpPacket.LIVE_DEVICE_STATUS_RESP,
                            liveResponse(firstRequestId.get(), deviceInfo(0)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] targetStatus() {
        byte[] name = "AhaKey 507C".getBytes(StandardCharsets.UTF_8);
        byte[] mac = "D4:6C:50:7C:C7:B1".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[1 + 1 + name.length + 1 + mac.length + 1];
        int offset = 0;
        data[offset++] = 1;
        data[offset++] = (byte) name.length;
        System.arraycopy(name, 0, data, offset, name.length);
        offset += name.length;
        data[offset++] = (byte) mac.length;
        System.arraycopy(mac, 0, data, offset, mac.length);
        offset += mac.length;
        data[offset] = 1;
        return data;
    }

    private static byte[] deviceInfo(int switchState) {
        return new byte[] { 75, 50, 1, 0, 2, 1, (byte) switchState, 35 };
    }

    private static byte[] liveResponse(byte[] requestId, byte[] status) {
        byte[] data = new byte[requestId.length + status.length];
        System.arraycopy(requestId, 0, data, 0, requestId.length);
        System.arraycopy(status, 0, data, requestId.length, status.length);
        return data;
    }

    private static void write(OutputStream output, byte type, byte[] data) throws Exception {
        output.write(BleTcpPacket.encode(type, data));
        output.flush();
    }

    private static boolean readFully(InputStream input, byte[] buffer) throws Exception {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
