package shadow.http.server;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * A Writer for HttpRequest responses that accumulates up to a threshold before committing.
 *
 * If closed before the threshold is reached, the accumulated content is sent via
 * HttpRequest.writeString(), which can avoid chunking/compression for small responses.
 *
 * If the buffer fills or flush() is called, the response is committed via
 * HttpRequest.responseBody() and subsequent writes go through a BufferedWriter
 * wrapping an OutputStreamWriter on the response OutputStream.
 */
public class HttpOutput extends Writer {

    private final HttpRequest request;
    private final int threshold;
    private final int bufferSize;

    // pre-commit buffer
    private char[] buf;
    private int pos;

    // post-commit writer
    private BufferedWriter committed;
    private boolean closed;

    public HttpOutput(HttpRequest request, int threshold) {
        this(request, threshold, threshold);
    }

    public HttpOutput(HttpRequest request, int threshold, int bufferSize) {
        this.request = request;
        this.threshold = threshold;
        this.bufferSize = bufferSize;
        this.buf = new char[threshold];
        this.pos = 0;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Writer is closed");
        }
    }

    private void commit() throws IOException {
        if (committed != null) {
            return;
        }

        OutputStream out = request.responseBody();
        committed = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), bufferSize);

        // flush any accumulated pre-commit data
        if (pos > 0) {
            committed.write(buf, 0, pos);
            buf = null;
            pos = 0;
        }
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        ensureOpen();

        if (committed != null) {
            committed.write(cbuf, off, len);
            return;
        }

        int remaining = threshold - pos;

        if (len < remaining) {
            System.arraycopy(cbuf, off, buf, pos, len);
            pos += len;
        } else {
            // would exceed threshold, commit
            commit();
            committed.write(cbuf, off, len);
        }
    }

    @Override
    public void write(int c) throws IOException {
        ensureOpen();

        if (committed != null) {
            committed.write(c);
            return;
        }

        if (pos < threshold) {
            buf[pos++] = (char) c;
        } else {
            commit();
            committed.write(c);
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        ensureOpen();

        if (committed != null) {
            committed.write(str, off, len);
            return;
        }

        int remaining = threshold - pos;

        if (len < remaining) {
            str.getChars(off, off + len, buf, pos);
            pos += len;
        } else {
            commit();
            committed.write(str, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();

        commit();
        committed.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;

        if (committed != null) {
            committed.close();
        } else {
            // under threshold, send as single writeString
            String s = new String(buf, 0, pos);
            buf = null;
            pos = 0;
            request.writeString(s);
        }
    }
}
