package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Unsynchronized buffered OutputStream for single-threaded or externally locked connection writers.
 * Avoids the per-call InternalLock overhead of {@link java.io.BufferedOutputStream}.
 */
class UnsyncBufferedOutputStream extends OutputStream {

    private final OutputStream out;
    private final byte[] buf;
    private int count;

    UnsyncBufferedOutputStream(OutputStream out, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        this.out = out;
        this.buf = new byte[size];
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buf, 0, count);
            count = 0;
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (count >= buf.length) {
            flushBuffer();
        }
        buf[count++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len >= buf.length) {
            flushBuffer();
            out.write(b, off, len);
            return;
        }
        if (len > buf.length - count) {
            flushBuffer();
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try (OutputStream ignored = out) {
            flush();
        }
    }
}
