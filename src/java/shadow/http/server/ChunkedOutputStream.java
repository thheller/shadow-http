package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

class ChunkedOutputStream extends OutputStream {
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream out;
    private final boolean flushEveryChunk;

    // this should not use a temporary buffer and the user may want to send chunks of arbitrary size
    // for text/event-stream or so. so we just send whatever chunk size we get from user
    // leaving the user responsible for choosing correct sizes instead of accumulating here and sending fixed size


    public ChunkedOutputStream(OutputStream out, boolean flushEveryChunk) {
        this.out = out;
        this.flushEveryChunk = flushEveryChunk;
    }

    @Override
    public void write(int b) throws IOException {
        throw new IllegalStateException("not allowed to write single bytes in chunked encoding");
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        writeChunk(b, off, len);
    }

    @Override
    public void write(byte[] b) throws IOException {
        writeChunk(b, 0, b.length);
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.write(LAST_CHUNK);
        out.flush();
        out.close();
    }

    private void writeChunk(byte[] buffer, int off, int len) throws IOException {
        int total = len - off;

        String hex = Integer.toHexString(total);
        out.write(hex.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
        out.write(buffer, off, len);
        out.write(CRLF);

        if (flushEveryChunk) {
            out.flush();
        }
    }
}