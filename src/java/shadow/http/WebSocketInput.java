package shadow.http;

import java.io.*;

/**
 * WebSocket frame reader for the server side, per RFC 6455 Section 5.
 * Assumes the connection has already been upgraded (handshake completed).
 * Reads individual frames from the underlying InputStream.
 */
public class WebSocketInput {
    /**
     * Section 10.4: Implementation-specific limit on frame payload size.
     * Default 16 MB; adjust as needed.
     */
    private static final long MAX_PAYLOAD_LENGTH = 16 * 1024 * 1024;

    /**
     * Section 5.5: Control frames MUST have a payload length of 125 bytes or less.
     */
    private static final int MAX_CONTROL_FRAME_PAYLOAD = 125;

    private final InputStream in;

    public WebSocketInput(InputStream in) {
        this.in = in;
    }

    /**
     * Reads a single WebSocket frame from the input stream.
     *
     * <p>Per RFC 6455 Section 5.2, the base framing protocol wire format is:
     * <pre>
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-------+-+-------------+-------------------------------+
     * |F|R|R|R| opcode|M| Payload len |    Extended payload length    |
     * |I|S|S|S|  (4)  |A|     (7)     |             (16/64)           |
     * |N|V|V|V|       |S|             |   (if payload len==126/127)   |
     * | |1|2|3|       |K|             |                               |
     * +-+-+-+-+-------+-+-------------+ - - - - - - - - - - - - - - -+
     * |     Extended payload length continued, if payload len == 127  |
     * + - - - - - - - - - - - - - - -+-------------------------------+
     * |                               |Masking-key, if MASK set to 1 |
     * +-------------------------------+-------------------------------+
     * | Masking-key (continued)       |          Payload Data         |
     * +-------------------------------- - - - - - - - - - - - - - - -+
     * :                     Payload Data continued ...                :
     * + - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -+
     * |                     Payload Data (continued)                  |
     * +---------------------------------------------------------------+
     * </pre>
     *
     * @return the parsed Frame, or null if the stream has ended cleanly
     * @throws IOException on I/O errors
     * @throws WebSocketProtocolException on protocol violations
     */
    public WebSocketFrame readFrame() throws IOException {
        // --- Byte 0: FIN, RSV1-3, opcode ---
        int b0 = readByteOrEof();
        if (b0 == -1) {
            return null; // clean stream end
        }

        boolean fin = (b0 & 0x80) != 0;
        boolean rsv1 = (b0 & 0x40) != 0;
        boolean rsv2 = (b0 & 0x20) != 0;
        boolean rsv3 = (b0 & 0x10) != 0;
        int opcode = b0 & 0x0F;

        // Section 5.2: RSV1-3 MUST be 0 unless an extension is negotiated
        // that defines meanings for non-zero values.
        // Since we don't support extensions, fail on non-zero RSV bits.
        if (rsv1 || rsv2 || rsv3) {
            throw new WebSocketProtocolException(
                    1002, "Reserved bits set without negotiated extension: RSV1=" + rsv1 + " RSV2=" + rsv2 + " RSV3=" + rsv3);
        }

        // Validate opcode
        validateOpcode(opcode);

        // --- Byte 1: MASK, Payload length (7 bits) ---
        int b1 = readByte();
        boolean masked = (b1 & 0x80) != 0;
        int payloadLen7 = b1 & 0x7F;

        // Section 5.1: server MUST close connection if frame is not masked
        // "a server MUST NOT mask any frames that it sends to the client"
        // "A server MUST close a connection upon receiving a frame that is not masked."
        if (!masked) {
            throw new WebSocketProtocolException(
                    1002, "Client frame is not masked (Section 5.1)");
        }

        // --- Extended payload length ---
        long payloadLength = readPayloadLength(payloadLen7);

        // Section 5.5: control frames MUST have payload <= 125
        boolean isControl = (opcode & 0x08) != 0;
        if (isControl) {
            if (payloadLength > MAX_CONTROL_FRAME_PAYLOAD) {
                throw new WebSocketProtocolException(
                        1002, "Control frame payload exceeds 125 bytes (Section 5.5)");
            }
            // Section 5.4/5.5: control frames MUST NOT be fragmented
            if (!fin) {
                throw new WebSocketProtocolException(
                        1002, "Control frame must not be fragmented (Section 5.5)");
            }
        }

        // Section 10.4: enforce implementation limit
        if (payloadLength > MAX_PAYLOAD_LENGTH) {
            throw new WebSocketProtocolException(
                    1009, "Frame payload too large: " + payloadLength + " bytes (max " + MAX_PAYLOAD_LENGTH + ")");
        }

        // --- Masking key (4 bytes, since masked is always true for client frames) ---
        byte[] maskingKey = readExact(4);

        // --- Payload data ---
        byte[] payload = readExact((int) payloadLength);

        // Section 5.3: unmasking — apply masking key
        // "j = i MOD 4"
        // "transformed-octet-i = original-octet-i XOR masking-key-octet-j"
        unmask(payload, maskingKey);

        return new WebSocketFrame(fin, rsv1, rsv2, rsv3, opcode, payload);
    }

    /**
     * Reads the extended payload length per Section 5.2.
     *
     * <ul>
     *   <li>If 0-125: that is the payload length</li>
     *   <li>If 126: the following 2 bytes (unsigned, network byte order) are the payload length</li>
     *   <li>If 127: the following 8 bytes (unsigned, network byte order) are the payload length;
     *       the most significant bit MUST be 0</li>
     * </ul>
     */
    private long readPayloadLength(int payloadLen7) throws IOException {
        if (payloadLen7 <= 125) {
            return payloadLen7;
        } else if (payloadLen7 == 126) {
            // 16-bit unsigned, network byte order
            int b0 = readByte();
            int b1 = readByte();
            return ((b0 & 0xFF) << 8) | (b1 & 0xFF);
        } else {
            // payloadLen7 == 127 → 64-bit unsigned, network byte order
            long length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | (readByte() & 0xFF);
            }
            // Section 5.2: the most significant bit MUST be 0
            if (length < 0) {
                throw new WebSocketProtocolException(
                        1002, "Payload length has most significant bit set (Section 5.2)");
            }
            return length;
        }
    }

    /**
     * Validates the opcode per Section 5.2.
     * Known opcodes: 0x0-0x2 (data), 0x8-0xA (control).
     * Reserved opcodes (0x3-0x7, 0xB-0xF) cause a protocol error.
     */
    private void validateOpcode(int opcode) throws WebSocketProtocolException {
        switch (opcode) {
            case WebSocketFrame.OPCODE_CONTINUATION:
            case WebSocketFrame.OPCODE_TEXT:
            case WebSocketFrame.OPCODE_BINARY:
            case WebSocketFrame.OPCODE_CLOSE:
            case WebSocketFrame.OPCODE_PING:
            case WebSocketFrame.OPCODE_PONG:
                return;
            default:
                throw new WebSocketProtocolException(
                        1002, "Unknown or reserved opcode: 0x" + Integer.toHexString(opcode));
        }
    }

    /**
     * Section 5.3: Unmask payload data in place.
     * <pre>
     * j = i MOD 4
     * transformed-octet-i = original-octet-i XOR masking-key-octet-j
     * </pre>
     */
    private static void unmask(byte[] payload, byte[] maskingKey) {
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ maskingKey[i & 0x3]);
        }
    }

    /**
     * Reads exactly {@code len} bytes from the input stream.
     *
     * @throws EOFException if the stream ends before all bytes are read
     */
    private byte[] readExact(int len) throws IOException {
        if (len == 0) {
            return new byte[0];
        }
        byte[] buf = new byte[len];
        int offset = 0;
        while (offset < len) {
            int read = in.read(buf, offset, len - offset);
            if (read == -1) {
                throw new EOFException("Unexpected end of stream while reading WebSocket frame data");
            }
            offset += read;
        }
        return buf;
    }

    /**
     * Reads a single byte, returning -1 on EOF (for initial frame byte detection).
     */
    private int readByteOrEof() throws IOException {
        return in.read();
    }

    /**
     * Reads a single byte, throwing EOFException on EOF.
     */
    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of stream while reading WebSocket frame");
        }
        return b;
    }

}