package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

class ChunkedOutputStream extends OutputStream {
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream out;

    private boolean finished;

    // this should not use a temporary buffer and the user may want to send chunks of arbitrary size
    // for text/event-stream or so. so we just send whatever chunk size we get from user
    // leaving the user responsible for choosing correct sizes instead of accumulating here and sending fixed size


    public ChunkedOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (finished) {
            throw new IOException("Chunked stream is closed");
        }
        if (len > 0) {
            writeChunk(b, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            finish();
            out.close();
        }
    }

    // The proxy still needs the socket to read the response.
    void finish() throws IOException {
        if (!finished) {
            finished = true;
            out.write(LAST_CHUNK);
            out.flush();
        }
    }

    private void writeChunk(byte[] buffer, int off, int len) throws IOException {
        String hex = Integer.toHexString(len);
        out.write(hex.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(buffer, off, len);
        out.write(CRLF);
    }
}
