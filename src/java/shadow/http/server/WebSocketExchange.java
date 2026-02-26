package shadow.http.server;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

public class WebSocketExchange implements WebSocketConnection, Exchange {
    private static final int MAX_FRAME_SIZE = 1000000;
    // minimum size of message before compression is used
    private static final int COMPRESSION_MIN_SIZE = 256;

    private final Connection connection;

    private WebSocketHandler handler;

    private final ReentrantLock lock = new ReentrantLock();

    final InputStream in;
    final OutputStream out;
    final WebSocketInput wsIn;

    /**
     * Optional permessage-deflate context. Null when compression is not negotiated.
     */
    final WebSocketCompression wsCompression;

    boolean wasClosed = false;
    int closeStatusCode = 1006; // 1006 = abnormal closure (no close frame received)
    String closeReason = "";

    public WebSocketExchange(Connection connection, WebSocketHandler handler, WebSocketCompression webSocketCompression) throws IOException {
        this.connection = connection;
        this.handler = handler;
        this.in = connection.getInputStream();
        this.out = connection.getOutputStream();
        this.wsIn = new WebSocketInput(this.in, webSocketCompression);
        this.wsCompression = webSocketCompression;
    }

    @Override
    public void process() throws IOException {
        try {
            this.handler = this.handler.start(this);

            // State for assembling fragmented messages
            boolean inFragmentedMessage = false;
            boolean fragmentedCompressed = false;
            int fragmentedOpcode = -1;
            ByteArrayOutputStream fragmentBuffer = null;

            for (; ; ) {
                WebSocketFrame frame = null;

                try {
                    frame = wsIn.readFrame();
                } catch (WebSocketProtocolException e) {
                    sendClose(e.getStatusCode());
                }

                if (frame == null) {
                    break;
                }

                if (frame.isControl()) {
                    // RFC 6455 Section 5.5: control frames may appear in the middle of a fragmented message
                    if (frame.isClose()) {
                        int code = frame.getCloseStatusCode() == 1005 ? 1000 : frame.getCloseStatusCode();
                        closeReason = frame.getCloseReason();
                        sendClose(code);
                    } else if (frame.isPing()) {
                        this.handler = handler.onPing(frame.payload);
                    } else if (frame.isPong()) {
                        this.handler = handler.onPong(frame.payload);
                    }
                } else if (!frame.isContinuation() && frame.isFin()) {
                    // Simple unfragmented data frame
                    byte[] payload = frame.payload;
                    if (wsCompression != null && frame.rsv1) {
                        // Per-message compressed – decompress the payload (RFC 7692 Section 6.2)
                        payload = wsCompression.decompress(frame.payload);
                    }
                    dispatchMessage(frame.opcode, payload);
                } else if (!frame.isContinuation() && !frame.isFin()) {
                    // First fragment of a fragmented message
                    inFragmentedMessage = true;
                    fragmentedCompressed = wsCompression != null && frame.rsv1;
                    fragmentedOpcode = frame.opcode;
                    fragmentBuffer = new ByteArrayOutputStream();
                    fragmentBuffer.write(frame.payload);
                } else if (frame.isContinuation()) {
                    if (!inFragmentedMessage || fragmentBuffer == null) {
                        sendClose(1002);
                        closeReason = "Unexpected CONTINUATION Frame";
                        break;
                    }
                    fragmentBuffer.write(frame.payload);
                    if (frame.isFin()) {
                        // Last fragment – assemble and optionally decompress
                        byte[] assembled = fragmentBuffer.toByteArray();
                        if (fragmentedCompressed) {
                            assembled = wsCompression.decompress(assembled);
                        }
                        int opcode = fragmentedOpcode;
                        inFragmentedMessage = false;
                        fragmentedCompressed = false;
                        fragmentedOpcode = -1;
                        fragmentBuffer = null;
                        dispatchMessage(opcode, assembled);
                    }
                }

                if (wasClosed) {
                    break;
                }
            }

        } catch (EOFException e) {
            // ignore
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (wsCompression != null) {
                wsCompression.close();
            }
        }

        handler.onClose(closeStatusCode, closeReason);
    }

    private void dispatchMessage(int opcode, byte[] payload) throws IOException {
        if (opcode == WebSocketFrame.OPCODE_TEXT) {
            this.handler = handler.onText(new String(payload, StandardCharsets.UTF_8));
        } else if (opcode == WebSocketFrame.OPCODE_BINARY) {
            this.handler = handler.onBinary(payload);
        }
    }

    @Override
    public boolean isOpen() {
        return !wasClosed && connection.isActive();
    }

    @Override
    public void sendText(String text) throws IOException {
        lock.lock();
        try {
            // need to lock in case multiple threads try to send messages
            sendTextInternal(text);
        } finally {
            lock.unlock();
        }
    }

    private void sendTextInternal(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // Only compress when compression was negotiated AND the payload is large enough
        // to benefit.  RFC 7692 Section 6.1 explicitly allows skipping compression for
        // any individual message (RSV1=0); small messages typically expand under deflate.
        boolean rsv1 = wsCompression != null && bytes.length >= COMPRESSION_MIN_SIZE;

        if (rsv1) {
            // Compress the whole message payload (RFC 7692 Section 7.2.1), then send as
            // frame(s) with RSV1=1 on the first frame only (the "Per-Message Compressed" bit).
            bytes = wsCompression.compress(bytes);
        }

        int length = bytes.length;

        if (length <= MAX_FRAME_SIZE) {
            sendFrame(out, true, rsv1, WebSocketFrame.OPCODE_TEXT, bytes, 0, length);
            return;
        }

        // Send first frame with OPCODE_TEXT and fin=false
        int offset = 0;
        sendFrame(out, false, rsv1, WebSocketFrame.OPCODE_TEXT, bytes, offset, MAX_FRAME_SIZE);
        offset += MAX_FRAME_SIZE;

        // Send continuation frames (RSV1 MUST NOT be set on non-first fragments per RFC 7692 Section 6.1)
        while (offset < length) {
            int end = Math.min(offset + MAX_FRAME_SIZE, length);
            boolean fin = (end == length);
            sendFrame(out, fin, false, WebSocketFrame.OPCODE_CONTINUATION, bytes, offset, end - offset);
            offset = end;
        }
    }

    public static void sendFrame(OutputStream out, boolean fin, boolean rsv1, int opcode, byte[] payload, int offset, int length) throws IOException {
        int b0 = (fin ? 0x80 : 0)
                | (rsv1 ? 0x40 : 0)
                | (opcode & 0x0F);

        out.write(b0);

        if (length <= 125) {
            out.write(length);
        } else if (length <= 0xFFFF) {
            out.write(126);
            out.write(((length >> 8) & 0xFF));
            out.write((length & 0xFF));
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write(((length >> (8 * i)) & 0xFF));
            }
        }

        out.write(payload, offset, length);
        out.flush();
    }


    @Override
    public void sendPing(byte[] payload) throws IOException {
        lock.lock();
        try {
            sendFrame(out, true, false, WebSocketFrame.OPCODE_PING, payload, 0, payload.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void sendPong(byte[] payload) throws IOException {
        lock.lock();
        try {
            sendFrame(out, true, false, WebSocketFrame.OPCODE_PONG, payload, 0, payload.length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void sendClose(int statusCode) throws IOException {
        lock.lock();
        try {
            sendCloseInternal(statusCode);
        } finally {
            lock.unlock();
        }
    }

    void sendCloseInternal(int statusCode) throws IOException {
        byte[] payload = new byte[2];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        sendFrame(out, true, false, WebSocketFrame.OPCODE_CLOSE, payload, 0, payload.length);
        wasClosed = true;
        closeStatusCode = statusCode;
    }
}
