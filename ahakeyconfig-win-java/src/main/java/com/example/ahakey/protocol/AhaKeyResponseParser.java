package com.example.ahakey.protocol;

public final class AhaKeyResponseParser {
    public record CommandResponse(byte cmd, byte status, byte[] payload) {
    }

    public record PictureState(
        int mode,
        int startIndex,
        int picLength,
        int frameInterval,
        int allModeMaxPic
    ) {
    }

    private AhaKeyResponseParser() {
    }

    public static CommandResponse parseCommandResponse(byte[] frame) {
        if (!AhaKeyProtocol.isValidFrame(frame) || frame.length < 6) {
            return null;
        }
        byte cmd = frame[2];
        byte status = frame[3];
        int payloadLen = frame.length - 6;
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            System.arraycopy(frame, 4, payload, 0, payloadLen);
        }
        return new CommandResponse(cmd, status, payload);
    }

    public static PictureState parsePictureState(byte[] payload) {
        if (payload == null || payload.length < 9) {
            return null;
        }
        // parseCommandResponse already removes the command status byte. The first
        // byte here is always the OLED mode; mode 0 is a valid value (OLED 1).
        int mode = payload[0] & 0xFF;
        int startIndex = (payload[1] & 0xFF) | ((payload[2] & 0xFF) << 8);
        int picLength = (payload[3] & 0xFF) | ((payload[4] & 0xFF) << 8);
        int frameInterval = (payload[5] & 0xFF) | ((payload[6] & 0xFF) << 8);
        int allModeMaxPic = (payload[7] & 0xFF) | ((payload[8] & 0xFF) << 8);
        return new PictureState(mode, startIndex, picLength, frameInterval, allModeMaxPic);
    }
}
