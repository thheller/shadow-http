package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream that reads exactly {@code contentLength} bytes from the
 * underlying stream, then returns EOF. Does not close the underlying stream
 * so the connection can be reused for the next request.
 */
public class ContentLengthInputStream extends InputStream {

    private final InputStream in;
    private long remaining;

    ContentLengthInputStream(InputStream in, long contentLength) {
        this.in = in;
        this.remaining = contentLength;
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int b = in.read();
        if (b == -1) {
            remaining = 0;
            return -1;
        }
        remaining--;
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (remaining <= 0) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        int n = in.read(buf, off, toRead);
        if (n == -1) {
            remaining = 0;
            return -1;
        }
        remaining -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(in.available(), remaining);
    }

    @Override
    public void close() throws IOException {
        // Drain any remaining bytes so the connection is left in a clean state
        // for keep-alive. Do NOT close the underlying stream.
        if (remaining > 0) {
            byte[] skip = new byte[4096];
            while (remaining > 0) {
                int n = read(skip, 0, (int) Math.min(skip.length, remaining));
                if (n == -1) {
                    break;
                }
            }
        }
    }
}

