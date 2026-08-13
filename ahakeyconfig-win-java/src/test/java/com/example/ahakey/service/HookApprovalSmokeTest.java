package com.example.ahakey.service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local socket smoke test for Codex PermissionRequest switch semantics. */
public final class HookApprovalSmokeTest {

    private HookApprovalSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicBoolean auto = new AtomicBoolean(true);
        HookDispatchServer server = new HookDispatchServer(null, 0);
        server.setAutoApprovalSupplier(auto::get);
        server.start();
        try {
            String automatic = request(server.getActualPort(), "CodexPermissionRequest");
            require(automatic.contains("\"autoApproved\":true"), "automatic mode did not approve");

            auto.set(false);
            String manual = request(server.getActualPort(), "CodexPermissionRequest");
            require(manual.contains("\"autoApproved\":false"), "manual mode did not request AhaKey confirmation");

            System.out.println("Hook approval smoke test passed");
        } finally {
            server.stop();
        }
    }

    private static String request(int port, String event) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
             )) {
            writer.println(event);
            return reader.readLine();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
