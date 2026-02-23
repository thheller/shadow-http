package shadow.http;

import java.util.List;
import java.util.Map;

/**
 * Represents a single chunk from a chunked Transfer-Encoding message body.
 * Per RFC 9112 Section 7.1.
 *
 * If {@link #isLast()} returns true, the chunk data is empty and
 * {@link #trailers()} may contain trailer fields from the trailer section.
 */
public final class Chunk {

    private final byte[] data;
    private final Map<String, String> extensions;
    private final List<Header> trailers;

    Chunk(byte[] data, Map<String, String> extensions, List<Header> trailers) {
        this.data = data;
        this.extensions = extensions;
        this.trailers = trailers;
    }

    /**
     * The chunk data bytes. Empty for the terminal (last) chunk.
     */
    public byte[] data() {
        return data;
    }

    /**
     * Chunk extensions as an ordered name→value map.
     * Values may be null for extensions without a value.
     * Per RFC 9112 Section 7.1.1.
     */
    public Map<String, String> extensions() {
        return extensions;
    }

    /**
     * Trailer fields collected from the terminal chunk's trailer section.
     * Empty for non-terminal chunks.
     * Per RFC 9112 Section 7.1.2.
     */
    public List<Header> trailers() {
        return trailers;
    }

    /**
     * Returns true if this is the terminal zero-length chunk.
     */
    public boolean isLast() {
        return data.length == 0;
    }
}