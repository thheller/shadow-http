package shadow.http.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public class WebSocketExchange implements WebSocketConnection, Exchange {
    private static final int MAX_FRAME_SIZE = 1000000;
    // minimum size of message before compression is used
    private static final int COMPRESSION_MIN_SIZE = 256;

    private final Connection connection;

    private WebSocketHandler handler;
    private final int maxMessageSize;

    private final ReentrantLock writeLock = new ReentrantLock();

    final InputStream in;
    final OutputStream out;
    final WebSocketInput wsIn;

    /**
     * Optional permessage-deflate context. Null when compression is not negotiated.
     */
    final PerMessageDeflate perMessageDeflate;

    // written under writeLock, but isOpen may be called from any thread
    volatile boolean wasClosed = false;
    int closeStatusCode = 1006; // 1006 = abnormal closure (no close frame received)
    String closeReason = "";

    public WebSocketExchange(Connection connection, InputStream in, WebSocketHandler handler, PerMessageDeflate perMessageDeflate) throws IOException {
        this.connection = connection;
        this.maxMessageSize = connection.getServer().getConfig().getMaximumWebSocketMessageSize();
        this.in = in;
        this.handler = handler;
        this.out = new UnsyncBufferedOutputStream(connection.getOutputStream(), 8192);
        this.wsIn = new WebSocketInput(this.in, perMessageDeflate);
        this.perMessageDeflate = perMessageDeflate;
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
                int assembledSize = fragmentBuffer == null ? 0 : fragmentBuffer.size();
                WebSocketFrame frame = wsIn.readFrame(maxMessageSize - assembledSize);
                if (frame == null) {
                    break;
                }

                if (inFragmentedMessage && !frame.isControl() && !frame.isContinuation()) {
                    throw new WebSocketProtocolException(1002, "Expected continuation frame");
                }
                if (frame.isControl()) {
                    // RFC 6455 Section 5.5: control frames may appear in the middle of a fragmented message
                    if (frame.isClose()) {
                        int code = frame.getCloseStatusCode() == 1005 ? 1000 : frame.getCloseStatusCode();
                        closeReason = frame.getCloseReason();
                        sendClose(code);
                    } else if (frame.isPing()) {
                        handler.onPing(frame.payload);
                    } else if (frame.isPong()) {
                        handler.onPong(frame.payload);
                    }
                } else if (!frame.isContinuation() && frame.isFin()) {
                    // Simple unfragmented data frame
                    byte[] payload = frame.payload;
                    if (perMessageDeflate != null && frame.rsv1) {
                        // Per-message compressed – decompress the payload (RFC 7692 Section 6.2)
                        payload = perMessageDeflate.decompress(frame.payload, maxMessageSize);
                    }
                    dispatchMessage(frame.opcode, payload);
                } else if (!frame.isContinuation() && !frame.isFin()) {
                    // First fragment of a fragmented message
                    inFragmentedMessage = true;
                    fragmentedCompressed = perMessageDeflate != null && frame.rsv1;
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
                            assembled = perMessageDeflate.decompress(assembled, maxMessageSize);
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

        } catch (WebSocketProtocolException e) {
            closeReason = e.getMessage();
            try {
                sendClose(e.getStatusCode());
            } catch (IOException ignored) {
                // The peer may already have closed the transport.
            }
            reportError(e);
        } catch (EOFException e) {
            // ignore
        } catch (IOException e) {
            reportError(e);
        } catch (Exception e) {
            // handler code failed, RFC 6455 Section 7.4.1: 1011 = unexpected condition
            try {
                sendClose(1011);
            } catch (IOException ignored) {
                // The peer may already have closed the transport.
            }
            reportError(e);
        } finally {
            writeLock.lock();
            try {
                wasClosed = true;
                if (perMessageDeflate != null) {
                    perMessageDeflate.close();
                }
            } finally {
                writeLock.unlock();
            }
        }

        handler.onClose(closeStatusCode, closeReason);
    }

    // onClose must still be called if onError throws
    private void reportError(Throwable e) {
        try {
            handler.onError(e);
        } catch (Exception failed) {
            failed.addSuppressed(e);
            failed.printStackTrace();
        }
    }

    private void dispatchMessage(int opcode, byte[] payload) throws IOException {
        if (opcode == WebSocketFrame.OPCODE_TEXT) {
            handler.onText(new String(payload, StandardCharsets.UTF_8));
        } else if (opcode == WebSocketFrame.OPCODE_BINARY) {
            handler.onBinary(payload);
        }
    }

    @Override
    public boolean isOpen() {
        return !wasClosed && connection.isActive();
    }

    @Override
    public void sendText(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // need to lock in case multiple threads try to send messages
        writeLock.lock();
        try {
            sendInternal(WebSocketFrame.OPCODE_TEXT, bytes, 0, bytes.length);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendBinary(byte[] bytes, int offset, int length) throws IOException {
        // need to lock in case multiple threads try to send messages
        writeLock.lock();
        try {
            sendInternal(WebSocketFrame.OPCODE_BINARY, bytes, offset, length);
        } finally {
            writeLock.unlock();
        }
    }

    private void sendInternal(int opcode, byte[] bytes, int offset, int length) throws IOException {
        if (wasClosed) {
            throw new IOException("WebSocket is closed");
        }

        Objects.checkFromIndexSize(offset, length, bytes.length);

        // Only compress when compression was negotiated AND the payload is large enough
        // to benefit.  RFC 7692 Section 6.1 explicitly allows skipping compression for
        // any individual message (RSV1=0); small messages typically expand under deflate.
        boolean rsv1 = perMessageDeflate != null && length >= COMPRESSION_MIN_SIZE;

        if (rsv1) {
            // Compress the whole message payload (RFC 7692 Section 7.2.1), then send as
            // frame(s) with RSV1=1 on the first frame only (the "Per-Message Compressed" bit).
            bytes = perMessageDeflate.compress(bytes, offset, length);
            length = bytes.length;
            offset = 0;
        }

        do {
            int size = Math.min(length, MAX_FRAME_SIZE);
            sendFrame(out, size == length, rsv1, opcode, bytes, offset, size);
            offset += size;
            length -= size;
            opcode = WebSocketFrame.OPCODE_CONTINUATION;
            rsv1 = false;
        } while (length > 0);
    }

    public static void sendFrame(OutputStream out, boolean fin, boolean rsv1, int opcode, byte[] payload, int offset, int length) throws IOException {
        byte[] header = new byte[10];
        int headerLen;

        header[0] = (byte) ((fin ? 0x80 : 0)
                | (rsv1 ? 0x40 : 0)
                | (opcode & 0x0F));

        if (length <= 125) {
            header[1] = (byte) length;
            headerLen = 2;
        } else if (length <= 0xFFFF) {
            header[1] = 126;
            header[2] = (byte) ((length >> 8) & 0xFF);
            header[3] = (byte) (length & 0xFF);
            headerLen = 4;
        } else {
            header[1] = 127;
            // length is a positive int, so the high 32 bits of the 64-bit extended length are always 0
            header[2] = 0;
            header[3] = 0;
            header[4] = 0;
            header[5] = 0;
            header[6] = (byte) ((length >> 24) & 0xFF);
            header[7] = (byte) ((length >> 16) & 0xFF);
            header[8] = (byte) ((length >> 8) & 0xFF);
            header[9] = (byte) (length & 0xFF);
            headerLen = 10;
        }

        out.write(header, 0, headerLen);
        if (length > 0) {
            out.write(payload, offset, length);
        }
        out.flush();
    }


    @Override
    public void sendPing(byte[] payload) throws IOException {
        if (payload.length > 125) {
            throw new IllegalArgumentException("Control frame payload must be <= 125 bytes");
        }
        writeLock.lock();
        try {
            if (wasClosed) {
                throw new IOException("WebSocket is closed");
            }
            sendFrame(out, true, false, WebSocketFrame.OPCODE_PING, payload, 0, payload.length);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendPong(byte[] payload) throws IOException {
        if (payload.length > 125) {
            throw new IllegalArgumentException("Control frame payload must be <= 125 bytes");
        }
        writeLock.lock();
        try {
            if (wasClosed) {
                throw new IOException("WebSocket is closed");
            }
            sendFrame(out, true, false, WebSocketFrame.OPCODE_PONG, payload, 0, payload.length);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendClose(int statusCode) throws IOException {
        sendClose(statusCode, null);
    }

    @Override
    public void sendClose(int statusCode, String reason) throws IOException {
        writeLock.lock();
        try {
            sendCloseInternal(statusCode, reason);
        } finally {
            writeLock.unlock();
        }
    }

    void sendCloseInternal(int statusCode, String reason) throws IOException {
        // RFC 6455 Section 5.5.1: no more frames after a close frame, which includes another close.
        // happens when the peer answers a close we sent, or a handler closes more than once
        if (wasClosed) {
            return;
        }

        byte[] reasonBytes = reason == null ? new byte[0] : reason.getBytes(StandardCharsets.UTF_8);
        // control frame payloads are limited to 125 bytes, 2 of which are the status code
        int reasonLength = Math.min(reasonBytes.length, 123);
        if (reasonLength < reasonBytes.length) {
            // don't cut a multibyte UTF-8 sequence in half, continuation bytes are 10xxxxxx
            while (reasonLength > 0 && (reasonBytes[reasonLength] & 0xC0) == 0x80) {
                reasonLength--;
            }
        }

        byte[] payload = new byte[2 + reasonLength];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonLength);
        sendFrame(out, true, false, WebSocketFrame.OPCODE_CLOSE, payload, 0, payload.length);
        wasClosed = true;
        closeStatusCode = statusCode;
    }
}
