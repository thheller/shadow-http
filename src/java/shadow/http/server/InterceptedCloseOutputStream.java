package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;

class InterceptedCloseOutputStream extends OutputStream {
    private final HttpResponse response;
    private final OutputStream target;

    InterceptedCloseOutputStream(HttpResponse response, OutputStream target) {
        super();

        this.response = response;
        this.target = target;
    }

    @Override
    public void write(byte[] b) throws IOException {
        target.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        target.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        target.flush();
    }

    @Override
    public void close() throws IOException {
        target.flush();
        response.state = HttpResponse.State.COMPLETE;
    }

    @Override
    public void write(int b) throws IOException {
        target.write(b);
    }
}
