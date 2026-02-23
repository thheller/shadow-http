package shadow.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

public class WebSocketExchange implements WebSocketContext, Exchange {

    private final Connection connection;

    private WebSocketHandler handler;

    private final ReentrantLock lock = new ReentrantLock();

    final InputStream in;
    final OutputStream out;
    final WebSocketInput wsIn;

    boolean wasClosed = false;

    public WebSocketExchange(Connection connection, WebSocketHandler handler) throws IOException {
        this.connection = connection;
        this.handler = handler;
        this.in = connection.getInputStream();
        this.out = connection.getOutputStream();
        this.wsIn = new WebSocketInput(this.in);
    }

    @Override
    public void process() throws IOException {
        try {
            this.handler = this.handler.start(this);

            for (; ; ) {
                WebSocketFrame frame = wsIn.readFrame();

                if (frame == null) {
                    handler.stop();
                    break;
                } else {

                    if (frame.isClose()) {
                        // Echo the close frame back as required by RFC 6455 Section 5.5.1
                        close(frame.getCloseStatusCode() == 1005 ? 1000 : frame.getCloseStatusCode());
                    } else {
                        handler.handleFrame(this, frame);
                    }

                    if (wasClosed) {
                        break;
                    }
                }
            }
        } catch (EOFException e) {
            // ignore
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sendFrame(WebSocketFrame frame) throws IOException {
        // in case multiple threads try to send frames we need to lock
        // can't use synchronized since that wasn't ideal pre-java24, and we must be 21+
        lock.lock();
        try {
            sendFrameEx(frame);
        } finally {
            lock.unlock();
        }
    }

    public void sendFrameEx(WebSocketFrame frame) throws IOException {
        byte[] payload = (frame.payload == null) ? new byte[0] : frame.payload;

        int b0 = (frame.fin ? 0x80 : 0)
                | (frame.rsv1 ? 0x40 : 0)
                | (frame.rsv2 ? 0x20 : 0)
                | (frame.rsv3 ? 0x10 : 0)
                | (frame.opcode & 0x0F);

        out.write(b0);

        long len = payload.length;
        if (len <= 125) {
            out.write((int) len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((int) ((len >> 8) & 0xFF));
            out.write((int) (len & 0xFF));
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }

        out.write(payload);
        out.flush();
    }

    @Override
    public void close(int statusCode) throws IOException {
        sendFrame(WebSocketFrame.close(statusCode));
        wasClosed = true;
    }
}
