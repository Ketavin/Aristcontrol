package com.example.ahakey.protocol;

/** Regression checks for the OLED partition-state response parser. */
public final class AhaKeyResponseParserPictureStateSmokeTest {
    private AhaKeyResponseParserPictureStateSmokeTest() {
    }

    public static void main(String[] args) {
        parsesLiveOledOneResponseEndToEnd();
        parsesOtherModesFromStatusFreePayload();
        rejectsTruncatedPayload();
        System.out.println("AhaKey OLED partition-state parser smoke test passed");
    }

    private static void parsesLiveOledOneResponseEndToEnd() {
        byte[] liveFrame = new byte[] {
            (byte) 0xAA, (byte) 0xBB, (byte) 0x83, 0x00,
            0x00, 0x00, 0x00, 0x08, 0x00, 0x64, 0x00, 0x24, 0x01,
            (byte) 0xCC, (byte) 0xDD
        };

        AhaKeyResponseParser.CommandResponse response =
            AhaKeyResponseParser.parseCommandResponse(liveFrame);
        require(response != null, "live OLED 1 frame was not parsed");
        require((response.cmd() & 0xFF) == 0x83, "response command changed");
        require(response.status() == 0, "successful response status was not preserved");
        require(response.payload().length == 9, "status must be removed exactly once");

        AhaKeyResponseParser.PictureState state =
            AhaKeyResponseParser.parsePictureState(response.payload());
        require(state != null, "OLED 1 mode byte must not be mistaken for another status byte");
        require(state.mode() == 0, "OLED 1 mode was not parsed");
        require(state.startIndex() == 0, "OLED 1 start index was not parsed");
        require(state.picLength() == 8, "OLED 1 frame count was not parsed");
        require(state.frameInterval() == 100, "OLED 1 frame interval was not parsed");
        require(state.allModeMaxPic() == 292, "OLED total capacity was not parsed");
    }

    private static void parsesOtherModesFromStatusFreePayload() {
        AhaKeyResponseParser.PictureState state = AhaKeyResponseParser.parsePictureState(
            new byte[] {0x02, 0x0A, 0x00, 0x03, 0x00, (byte) 0xC8, 0x00, 0x24, 0x01}
        );
        require(state != null, "OLED 3 payload was not parsed");
        require(state.mode() == 2, "OLED 3 mode was not parsed");
        require(state.startIndex() == 10, "OLED 3 start index was not parsed");
        require(state.picLength() == 3, "OLED 3 frame count was not parsed");
        require(state.frameInterval() == 200, "OLED 3 frame interval was not parsed");
        require(state.allModeMaxPic() == 292, "OLED 3 capacity was not parsed");
    }

    private static void rejectsTruncatedPayload() {
        require(
            AhaKeyResponseParser.parsePictureState(new byte[8]) == null,
            "truncated OLED state payload must be rejected"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
