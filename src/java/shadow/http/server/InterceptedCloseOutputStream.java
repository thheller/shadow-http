package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;

class InterceptedCloseOutputStream extends OutputStream {
    private final HttpRequest request;
    private final OutputStream target;
    private boolean closed = false;

    InterceptedCloseOutputStream(HttpRequest request, OutputStream target) {
        super();

        this.request = request;
        this.target = target;
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Response stream is closed");
        }
        target.write(b, off, len);
        request.responseBytesWritten += len;
    }

    @Override
    public void flush() throws IOException {
        if (!closed) {
            target.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        target.flush();
        if (!request.autoChunk && request.responseLength >= 0
                && request.responseBytesWritten != request.responseLength) {
            // body length didn't match announced Content-Length; close connection so keep-alive stays clean
            request.closeAfter = true;
        }
        request.state = HttpRequest.State.COMPLETE;
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) {
            throw new IOException("Response stream is closed");
        }
        target.write(b);
        request.responseBytesWritten += 1;
    }
}
