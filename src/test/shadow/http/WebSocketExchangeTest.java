package shadow.http;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WebSocketExchange}.
 *
 * <p>Client-side frames MUST be masked (RFC 6455 Section 5.1).  We reuse the
 * server-side {@link WebSocketExchange#sendFrame} helper to build the wire
 * bytes and then XOR-mask them before feeding them into the fake connection's
 * input stream.
 *
 * <p>The test infrastructure writes all client frames into a
 * {@link ByteArrayOutputStream}, wraps that as a {@link ByteArrayInputStream},
 * and lets {@link WebSocketExchange#process()} run synchronously.  The server
 * frames written by the exchange are captured in a separate
 * {@link ByteArrayOutputStream} and decoded with {@link WebSocketInput}.
 */
public class WebSocketExchangeTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a single masked client frame using the same
     * {@link WebSocketExchange#sendFrame} wire format, then applies a
     * fixed masking key so that {@link WebSocketInput} can unmask it.
     *
     * <p>The masking key bytes are prepended right before the payload as
     * required by Section 5.2.  We construct the unmasked server frame
     * first (in a temporary stream), then patch the MASK bit and insert
     * the key + XOR-masked payload.
     */
    static void writeClientFrame(OutputStream out, boolean fin, int opcode, byte[] payload) throws IOException {
        // Use a zero masking key for simplicity – still satisfies the framing
        // requirement while keeping expected values easy to reason about.
        byte[] maskingKey = {0x11, 0x22, 0x33, 0x44};

        byte[] masked = Arrays.copyOf(payload, payload.length);
        for (int i = 0; i < masked.length; i++) {
            masked[i] = (byte) (masked[i] ^ maskingKey[i & 0x3]);
        }

        // Byte 0: FIN + opcode (no RSV bits for client frames in these tests)
        int b0 = (fin ? 0x80 : 0) | (opcode & 0x0F);
        out.write(b0);

        // Byte 1: MASK=1 + 7-bit payload length
        int len = masked.length;
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((len >> (8 * i)) & 0xFF);
            }
        }

        // Masking key
        out.write(maskingKey);

        // Masked payload
        out.write(masked);
    }

    /** Convenience: write a masked text frame. */
    static void writeClientTextFrame(OutputStream out, String text) throws IOException {
        writeClientFrame(out, true, WebSocketFrame.OPCODE_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience: write a masked binary frame. */
    static void writeClientBinaryFrame(OutputStream out, byte[] data) throws IOException {
        writeClientFrame(out, true, WebSocketFrame.OPCODE_BINARY, data);
    }

    /** Convenience: write a masked close frame with a 2-byte status code. */
    static void writeClientCloseFrame(OutputStream out, int statusCode) throws IOException {
        byte[] payload = {(byte) ((statusCode >> 8) & 0xFF), (byte) (statusCode & 0xFF)};
        writeClientFrame(out, true, WebSocketFrame.OPCODE_CLOSE, payload);
    }

    /**
     * Runs a WebSocketExchange with the given handler against the provided
     * client-frame bytes.
     *
     * @param handler       the server-side WebSocket handler
     * @param clientFrames  raw bytes of one or more masked client frames
     * @return the raw bytes that the server wrote to the output stream
     */
    static byte[] run(WebSocketHandler handler, byte[] clientFrames) throws IOException {
        Server server = new Server();

        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();
        InputStream clientIn = new ByteArrayInputStream(clientFrames);

        TestConnection con = new TestConnection(server, clientIn, serverOut);

        WebSocketExchange exchange = new WebSocketExchange(con, handler, null);
        exchange.process();

        return serverOut.toByteArray();
    }

    /**
     * Decodes all WebSocket frames from a raw byte array using
     * {@link WebSocketInput}.  Server frames are unmasked, so no masking
     * key is involved here.
     */
    static List<WebSocketFrame> decodeServerFrames(byte[] bytes) throws IOException {
        WebSocketInput wsIn = new WebSocketInput(new ByteArrayInputStream(bytes));
        List<WebSocketFrame> frames = new ArrayList<>();
        WebSocketFrame frame;
        // WebSocketInput expects masked frames (client→server); for the server
        // output (unmasked) we use a subclass that skips the mask check –
        // instead we parse manually to avoid that constraint.
        // Actually, we'll parse manually below since WebSocketInput enforces masking.
        return frames; // replaced by manual parsing below
    }

    /**
     * Manually decodes unmasked server-side WebSocket frames from a byte array.
     * Server frames follow the same framing format but with MASK=0.
     */
    static List<WebSocketFrame> parseServerFrames(byte[] bytes) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytes));
        List<WebSocketFrame> frames = new ArrayList<>();

        while (dis.available() > 0) {
            int b0 = dis.read();
            if (b0 == -1) break;

            boolean fin = (b0 & 0x80) != 0;
            boolean rsv1 = (b0 & 0x40) != 0;
            int opcode = b0 & 0x0F;

            int b1 = dis.read();
            boolean masked = (b1 & 0x80) != 0; // should be false for server frames
            assertFalse(masked, "Server must not mask frames (RFC 6455 Section 5.1)");
            int payloadLen7 = b1 & 0x7F;

            long payloadLength;
            if (payloadLen7 <= 125) {
                payloadLength = payloadLen7;
            } else if (payloadLen7 == 126) {
                payloadLength = ((dis.read() & 0xFF) << 8) | (dis.read() & 0xFF);
            } else {
                payloadLength = 0;
                for (int i = 0; i < 8; i++) {
                    payloadLength = (payloadLength << 8) | (dis.read() & 0xFF);
                }
            }

            byte[] payload = new byte[(int) payloadLength];
            dis.readFully(payload);

            frames.add(new WebSocketFrame(fin, rsv1, false, false, opcode, payload));
        }

        return frames;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    void serverSendTextReachesClient() throws IOException {
        // Handler echoes whatever text it receives back to the client, then
        // sends a close frame so the exchange loop terminates cleanly.
        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public WebSocketHandler onText(WebSocketContext ctx, String text) throws IOException {
                ctx.sendText("echo: " + text);
                ctx.sendClose(1000);
                return this;
            }
        };

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientTextFrame(clientFrames, "hello");
        // No explicit client close needed – the server will send one and set wasClosed.

        byte[] serverOutput = run(handler, clientFrames.toByteArray());
        List<WebSocketFrame> frames = parseServerFrames(serverOutput);

        // Expect at least a text frame followed by a close frame
        assertTrue(frames.size() >= 2, "Expected text + close frames, got " + frames.size());

        WebSocketFrame textFrame = frames.get(0);
        assertEquals(WebSocketFrame.OPCODE_TEXT, textFrame.opcode);
        assertTrue(textFrame.fin);
        assertEquals("echo: hello", textFrame.getText());

        WebSocketFrame closeFrame = frames.get(1);
        assertEquals(WebSocketFrame.OPCODE_CLOSE, closeFrame.opcode);
        assertEquals(1000, closeFrame.getCloseStatusCode());
    }

    @Test
    void clientCloseFrameEchoedBack() throws IOException {
        // A handler that does nothing; the client sends a close frame and we
        // verify the server echoes back a close frame with an appropriate code.
        WebSocketHandler handler = new WebSocketHandler() {};

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientCloseFrame(clientFrames, 1000);

        byte[] serverOutput = run(handler, clientFrames.toByteArray());
        List<WebSocketFrame> frames = parseServerFrames(serverOutput);

        assertFalse(frames.isEmpty(), "Expected at least one frame (close)");
        WebSocketFrame closeFrame = frames.get(0);
        assertEquals(WebSocketFrame.OPCODE_CLOSE, closeFrame.opcode);
        // Server must echo a valid close code
        int code = closeFrame.getCloseStatusCode();
        assertTrue(code >= 1000 && code < 5000, "Unexpected close code: " + code);
    }

    @Test
    void onCloseCalledWithCorrectCodeAfterClientClose() throws IOException {
        int[] receivedCode = {-1};
        String[] receivedReason = {null};

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void onClose(int statusCode, String reason) {
                receivedCode[0] = statusCode;
                receivedReason[0] = reason;
            }
        };

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientCloseFrame(clientFrames, 1001);

        run(handler, clientFrames.toByteArray());

        assertEquals(1001, receivedCode[0]);
        assertNotNull(receivedReason[0]);
    }

    @Test
    void multipleTextMessages() throws IOException {
        List<String> received = new ArrayList<>();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public WebSocketHandler onText(WebSocketContext ctx, String text) throws IOException {
                received.add(text);
                return this;
            }
        };

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientTextFrame(clientFrames, "first");
        writeClientTextFrame(clientFrames, "second");
        writeClientTextFrame(clientFrames, "third");
        writeClientCloseFrame(clientFrames, 1000);

        run(handler, clientFrames.toByteArray());

        assertEquals(List.of("first", "second", "third"), received);
    }

    @Test
    void binaryMessageDelivered() throws IOException {
        byte[][] receivedPayload = {null};

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public WebSocketHandler onBinary(WebSocketContext ctx, byte[] payload) {
                receivedPayload[0] = payload;
                return this;
            }
        };

        byte[] data = {0x01, 0x02, 0x03, (byte) 0xFF};

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientBinaryFrame(clientFrames, data);
        writeClientCloseFrame(clientFrames, 1000);

        run(handler, clientFrames.toByteArray());

        assertArrayEquals(data, receivedPayload[0]);
    }

    @Test
    void fragmentedTextMessage() throws IOException {
        List<String> received = new ArrayList<>();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public WebSocketHandler onText(WebSocketContext ctx, String text) {
                received.add(text);
                return this;
            }
        };

        // "hel" in first fragment (fin=false), "lo" in continuation (fin=true)
        byte[] part1 = "hel".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "lo".getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientFrame(clientFrames, false, WebSocketFrame.OPCODE_TEXT, part1);
        writeClientFrame(clientFrames, true, WebSocketFrame.OPCODE_CONTINUATION, part2);
        writeClientCloseFrame(clientFrames, 1000);

        run(handler, clientFrames.toByteArray());

        assertEquals(List.of("hello"), received);
    }

    @Test
    void serverFrameHasCorrectWireFormat() throws IOException {
        // Verify that sendFrame produces a correct server-side (unmasked) frame.
        // We call sendFrame directly and parse the output.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        WebSocketExchange.sendFrame(out, true, false, WebSocketFrame.OPCODE_TEXT, payload, 0, payload.length);

        byte[] bytes = out.toByteArray();

        // Byte 0: FIN=1, RSV=0, opcode=1 → 0x81
        assertEquals((byte) 0x81, bytes[0]);
        // Byte 1: MASK=0, length=4 → 0x04
        assertEquals((byte) 0x04, bytes[1]);
        // Payload
        assertArrayEquals(payload, Arrays.copyOfRange(bytes, 2, 2 + payload.length));
    }

    @Test
    void startHandlerIsInvoked() throws IOException {
        boolean[] started = {false};

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public WebSocketHandler start(WebSocketContext ctx) {
                started[0] = true;
                return this;
            }
        };

        ByteArrayOutputStream clientFrames = new ByteArrayOutputStream();
        writeClientCloseFrame(clientFrames, 1000);

        run(handler, clientFrames.toByteArray());

        assertTrue(started[0], "start() must be called before any message processing");
    }

    @Test
    void onCloseCalledEvenOnAbnormalEOF() throws IOException {
        int[] receivedCode = {-1};

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void onClose(int statusCode, String reason) {
                receivedCode[0] = statusCode;
            }
        };

        // Empty input → EOF immediately → abnormal closure (1006)
        run(handler, new byte[0]);

        assertEquals(1006, receivedCode[0]);
    }
}

