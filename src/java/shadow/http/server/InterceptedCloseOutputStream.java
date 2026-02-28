package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;

class InterceptedCloseOutputStream extends OutputStream {
    private final HttpRequest request;
    private final OutputStream target;

    InterceptedCloseOutputStream(HttpRequest request, OutputStream target) {
        super();

        this.request = request;
        this.target = target;
    }

    @Override
    public void write(byte[] b) throws IOException {
        target.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        target.write(b, off, len);
        request.responseBytesWritten += b.length;
    }

    @Override
    public void flush() throws IOException {
        target.flush();
    }

    @Override
    public void close() throws IOException {
        target.flush();
        request.state = HttpRequest.State.COMPLETE;
    }

    @Override
    public void write(int b) throws IOException {
        target.write(b);
        request.responseBytesWritten += 1;
    }
}
