package shadow.http.server;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

class ChunkedOutputStream extends OutputStream {
    private static final byte[] HEX_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };
    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] LAST_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream out;
    // max 8 hex digits for a positive int + \r\n
    private final byte[] headerBuf = new byte[10];
    private final byte[] singleByteChunk = {'1', '\r', '\n', 0, '\r', '\n'};

    private boolean finished;

    // this should not use a temporary buffer and the user may want to send chunks of arbitrary size
    // for text/event-stream or so. so we just send whatever chunk size we get from user
    // leaving the user responsible for choosing correct sizes instead of accumulating here and sending fixed size

    public ChunkedOutputStream(OutputStream out) {
        this.out = out;
        headerBuf[8] = '\r';
        headerBuf[9] = '\n';
    }

    @Override
    public void write(int b) throws IOException {
        if (finished) {
            throw new IOException("Chunked stream is closed");
        }
        singleByteChunk[3] = (byte) b;
        out.write(singleByteChunk, 0, singleByteChunk.length);
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
            out.write(LAST_CHUNK, 0, LAST_CHUNK.length);
            out.flush();
        }
    }

    private void writeChunk(byte[] buffer, int off, int len) throws IOException {
        int pos = 8;
        int v = len;
        do {
            headerBuf[--pos] = HEX_DIGITS[v & 0xF];
            v >>>= 4;
        } while (v != 0);
        out.write(headerBuf, pos, 10 - pos);
        out.write(buffer, off, len);
        out.write(CRLF, 0, 2);
    }
}
