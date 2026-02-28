package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream backed by chunked Transfer-Encoding.
 * Reads chunks via {@link HttpExchange#readChunk(int)} and presents them as a
 * contiguous byte stream. Returns EOF after the terminal (zero-length) chunk
 * has been consumed. Does not close the underlying stream.
 */
class ChunkedInputStream extends InputStream {

    private static final int DEFAULT_MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MB

    private final HttpExchange exchange;
    private final int maxChunkSize;

    private byte[] currentChunk;
    private int currentOffset;
    private boolean eof;

    ChunkedInputStream(HttpExchange exchange) {
        this(exchange, DEFAULT_MAX_CHUNK_SIZE);
    }

    ChunkedInputStream(HttpExchange exchange, int maxChunkSize) {
        this.exchange = exchange;
        this.maxChunkSize = maxChunkSize;
        this.currentChunk = null;
        this.currentOffset = 0;
        this.eof = false;
    }

    @Override
    public int read() throws IOException {
        if (eof) {
            return -1;
        }
        if (!ensureChunkData()) {
            return -1;
        }
        return currentChunk[currentOffset++] & 0xFF;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (eof) {
            return -1;
        }
        if (len == 0) {
            return 0;
        }
        if (!ensureChunkData()) {
            return -1;
        }
        int available = currentChunk.length - currentOffset;
        int toCopy = Math.min(len, available);
        System.arraycopy(currentChunk, currentOffset, buf, off, toCopy);
        currentOffset += toCopy;
        return toCopy;
    }

    @Override
    public int available() {
        if (eof || currentChunk == null) {
            return 0;
        }
        return currentChunk.length - currentOffset;
    }

    @Override
    public void close() throws IOException {
        // Drain remaining chunks so the connection is left in a clean state
        // for keep-alive. Do NOT close the underlying stream.
        while (!eof) {
            if (!ensureChunkData()) {
                break;
            }
            // Discard the current chunk
            currentChunk = null;
            currentOffset = 0;
        }
    }

    /**
     * Ensures that there is chunk data available to read from.
     * Returns true if data is available, false if EOF.
     */
    private boolean ensureChunkData() throws IOException {
        while (currentChunk == null || currentOffset >= currentChunk.length) {
            Chunk chunk = exchange.readChunk(maxChunkSize);
            if (chunk.isLast()) {
                eof = true;
                currentChunk = null;
                currentOffset = 0;
                return false;
            }
            currentChunk = chunk.data();
            currentOffset = 0;
            // In theory a non-last chunk always has data, but be safe
            if (currentChunk.length > 0) {
                return true;
            }
        }
        return true;
    }
}

