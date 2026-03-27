package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Buffers response bytes up to a configurable threshold before committing the
 * HTTP response headers.
 * <p>
 * If {@link #close()} is called before the buffer fills, the response is sent
 * with a fixed {@code Content-Length} header — no chunked encoding, no
 * compression.
 * <p>
 * If the buffer fills, or {@link #flush()} is called with pending data, the
 * response commits with {@code Transfer-Encoding: chunked} (and optionally
 * gzip compression), then all subsequent writes pass through directly.
 */
class HttpOutput extends OutputStream {
    private final HttpRequest request;

    private byte[] buf;
    private int count;
    private boolean committed;
    private boolean closed;

    private OutputStream out;

    HttpOutput(HttpRequest request, int bufferSize) {
        this.request = request;
        this.buf = new byte[bufferSize];
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        if (!committed && count >= buf.length) {
            commit();
        }
        if (committed) {
            out.write(b);
        } else {
            buf[count++] = (byte) b;
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();

        if (committed) {
            out.write(b, off, len);
            return;
        }

        int available = buf.length - count;
        if (len <= available) {
            System.arraycopy(b, off, buf, count, len);
            count += len;
        } else {
            // fill buffer, commit, write remainder directly
            System.arraycopy(b, off, buf, count, available);
            count = buf.length;
            commit();
            out.write(b, off + available, len - available);
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (!committed && count > 0) {
            commit();
        }
        if (out != null) {
            out.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;

        if (!committed) {
            // Response fits in buffer — send with Content-Length
            request.commitFixedLength(buf, count);
        } else {
            out.close();
        }
        buf = null;
    }

    private void commit() throws IOException {
        if (committed) return;
        committed = true;

        out = request.commitChunked();

        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
    }
}
