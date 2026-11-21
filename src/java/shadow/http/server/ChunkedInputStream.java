package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An InputStream backed by chunked Transfer-Encoding.
 * Streams chunk payloads directly from {@link HttpInput} without buffering
 * entire chunks in memory. Returns EOF after the terminal (zero-length) chunk
 * and trailers have been consumed. Does not close the underlying stream.
 */
public class ChunkedInputStream extends InputStream {

    static final int DEFAULT_MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MB (kept for backwards compatibility)

    private final HttpInput in;
    private final long maxChunkSize;

    private long remaining;
    private long chunkRemaining;
    private boolean needChunkEnd;
    private boolean failed;
    private boolean eof;

    public ChunkedInputStream(HttpInput in) {
        this(in, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public ChunkedInputStream(HttpInput in, int maxChunkSize) {
        this(in, maxChunkSize, Long.MAX_VALUE);
    }

    ChunkedInputStream(HttpInput in, long maxChunkSize, long maximumLength) {
        this.in = in;
        this.maxChunkSize = maxChunkSize;
        this.remaining = maximumLength;
        this.chunkRemaining = 0;
        this.needChunkEnd = false;
        this.eof = false;
    }

    @Override
    public int read() throws IOException {
        if (eof || !ensureChunkData()) {
            return -1;
        }
        int b = in.read();
        if (b == -1) {
            failed = true;
            throw new EOFException("Unexpected end of stream reading chunk data");
        }
        chunkRemaining--;
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, buf.length);
        if (len == 0) {
            return 0;
        }
        if (eof || !ensureChunkData()) {
            return -1;
        }
        int toRead = (int) Math.min(len, chunkRemaining);
        int n = in.read(buf, off, toRead);
        if (n == -1) {
            failed = true;
            throw new EOFException("Unexpected end of stream reading chunk data");
        }
        chunkRemaining -= n;
        return n;
    }

    @Override
    public int available() throws IOException {
        if (eof || chunkRemaining <= 0) {
            return 0;
        }
        return (int) Math.min(chunkRemaining, in.available());
    }

    @Override
    public void close() throws IOException {
        // Drain remaining chunks so the connection is left in a clean state
        // for keep-alive. Do NOT close the underlying stream.
        if (eof) {
            return;
        }
        byte[] skip = new byte[4096];
        while (!eof) {
            if (!ensureChunkData()) {
                break;
            }
            while (chunkRemaining > 0) {
                int n = read(skip, 0, (int) Math.min(skip.length, chunkRemaining));
                if (n == -1) {
                    return;
                }
            }
        }
    }

    /**
     * Ensures that there is chunk data available to read from.
     * Returns true if data is available, false if EOF.
     */
    private boolean ensureChunkData() throws IOException {
        while (chunkRemaining == 0) {
            if (failed) throw new BadRequestException("Invalid chunked body");
            try {
                if (needChunkEnd) {
                    in.consumeChunkDataLineEnding();
                    needChunkEnd = false;
                }

                long chunkSize = in.readChunkHeader();
                if (chunkSize == 0) {
                    in.readChunkTrailers();
                    eof = true;
                    return false;
                }

                if (chunkSize > maxChunkSize || chunkSize > remaining) {
                    throw new BadRequestException("Chunk size too large: " + chunkSize);
                }

                remaining -= chunkSize;
                chunkRemaining = chunkSize;
                needChunkEnd = true;
            } catch (IOException e) {
                failed = true;
                throw e;
            }
        }
        return true;
    }
}

