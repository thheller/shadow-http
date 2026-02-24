package shadow.http.server;

import java.nio.charset.StandardCharsets;

/**
 * Represents a single WebSocket frame as defined in Section 5.2.
 */
public class WebSocketFrame {
    // Opcodes (Section 5.2)
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    // 0x3-0x7 reserved for further non-control frames
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;
    // 0xB-0xF reserved for further control frames

    public final boolean fin;
    public final boolean rsv1;
    public final boolean rsv2;
    public final boolean rsv3;
    public final int opcode;
    public final byte[] payload;

    public WebSocketFrame(boolean fin, boolean rsv1, boolean rsv2, boolean rsv3, int opcode, byte[] payload) {
        this.fin = fin;
        this.rsv1 = rsv1;
        this.rsv2 = rsv2;
        this.rsv3 = rsv3;
        this.opcode = opcode;
        this.payload = payload;
    }

    public static WebSocketFrame text(byte[] payload) {
        return new WebSocketFrame(true, false, false, false, OPCODE_TEXT, payload);
    }

    public static WebSocketFrame close(int statusCode) {
        byte[] payload = new byte[2];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        return new WebSocketFrame(true, false, false, false, OPCODE_CLOSE, payload);
    }

    public boolean isControl() {
        // Section 5.5: opcodes 0x8-0xF are control frames
        return (opcode & 0x08) != 0;
    }

    public boolean isFin() {
        return fin;
    }

    public boolean isText() {
        return opcode == OPCODE_TEXT;
    }

    public boolean isBinary() {
        return opcode == OPCODE_BINARY;
    }

    public boolean isPing() {
        return opcode == OPCODE_PING;
    }

    public boolean isPong() {
        return opcode == OPCODE_PONG;
    }

    public boolean isClose() {
        return opcode == OPCODE_CLOSE;
    }

    public boolean isContinuation() {
        return opcode == OPCODE_CONTINUATION;
    }

    /**
     * For Close frames (Section 5.5.1): extract the 2-byte status code, if present.
     * Returns -1 if no status code is present.
     */
    public int getCloseStatusCode() {
        if (opcode != OPCODE_CLOSE) {
            return -1;
        }
        if (payload.length < 2) {
            // Section 7.1.5: no status code → considered 1005
            return 1005;
        }
        return ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
    }

    /**
     * For Close frames (Section 5.5.1 / 7.1.6): extract the UTF-8 close reason.
     * Returns empty string if no reason is present.
     */
    public String getCloseReason() {
        if (opcode != OPCODE_CLOSE || payload.length <= 2) {
            return "";
        }
        return new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getText() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
