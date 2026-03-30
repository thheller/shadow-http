package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HttpInput extends InputStream {

    private static final int CR = '\r';
    private static final int LF = '\n';
    private static final int SP = ' ';
    private static final int HTAB = '\t';
    private static final int COLON = ':';

    private static final boolean[] TCHAR = new boolean[256];
    private static final boolean[] HEADER_VALUE_OCTET = new boolean[256];

    // target-byte: anything > 0x20 and != 0x7F
    private static final boolean[] TARGET_BYTE = new boolean[256];

    static {
        for (char c = 'A'; c <= 'Z'; c++) TCHAR[c] = true;
        for (char c = 'a'; c <= 'z'; c++) TCHAR[c] = true;
        for (char c = '0'; c <= '9'; c++) TCHAR[c] = true;
        for (char c : new char[]{'!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~'}) {
            TCHAR[c] = true;
        }

        // SP, HTAB, VCHAR (0x21-0x7E), obs-text (0x80-0xFF)
        HEADER_VALUE_OCTET[SP] = true;
        HEADER_VALUE_OCTET[HTAB] = true;
        for (int i = 0x21; i <= 0x7E; i++) HEADER_VALUE_OCTET[i] = true;
        for (int i = 0x80; i <= 0xFF; i++) HEADER_VALUE_OCTET[i] = true;

        for (int i = 0x21; i <= 0xFF; i++) {
            if (i != 0x7F) TARGET_BYTE[i] = true;
        }
    }

    // Pre-interned version strings to avoid allocation on every request
    private static final String HTTP_1_1 = "HTTP/1.1";
    private static final String HTTP_1_0 = "HTTP/1.0";

    private final InputStream in;
    private final byte[] buffer;
    private int position;
    private int limit;

    public HttpInput(InputStream in, int bufferSize) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be > 0");
        }
        this.in = in;
        this.buffer = new byte[bufferSize];
    }


    public String readMethod() throws IOException {
        skipLeadingEmptyLines();

        int tokenStart = position;

        while (true) {
            // Fast path: scan buffered bytes directly
            while (position < limit) {
                int b = buffer[position] & 0xFF;

                if (TCHAR[b]) {
                    position++;
                    continue;
                }

                if (b == SP) {
                    if (position == tokenStart) {
                        throw new BadRequestException("Empty method token");
                    }

                    String method = asciiString(tokenStart, position - tokenStart);
                    position++;
                    return method;
                }

                throw new BadRequestException("Invalid octet in method token: 0x" + Integer.toHexString(b));
            }

            // Slow path: need more data
            tokenStart = ensureTokenByte(tokenStart, "HTTP method does not fit into the input buffer");
        }
    }

    public String readTarget() throws IOException {
        int tokenStart = position;

        while (true) {
            // Fast path: scan buffered bytes directly
            while (position < limit) {
                int b = buffer[position] & 0xFF;

                if (b == SP) {
                    if (position == tokenStart) {
                        throw new BadRequestException("Empty request-target");
                    }

                    String target = latin1String(tokenStart, position - tokenStart);
                    position++;
                    return target;
                }

                if (!TARGET_BYTE[b]) {
                    throw new BadRequestException("Invalid octet in request-target: 0x" + Integer.toHexString(b));
                }

                position++;
            }

            // Slow path: need more data
            tokenStart = ensureTokenByte(tokenStart, "HTTP request-target does not fit into the input buffer");
        }
    }

    /**
     * Reads the HTTP-version at the end of a request-line, consuming the trailing CRLF.
     *
     * @return the HTTP version string (e.g., "HTTP/1.1")
     * @throws IOException
     */
    public String readVersion() throws IOException {
        String version = parseVersionToken();
        consumeLineEnding("HTTP-version must be followed by CRLF");
        return version;
    }

    /**
     * Reads the HTTP-version at the start of a response status-line, consuming the trailing SP.
     *
     * @return the HTTP version string (e.g., "HTTP/1.1")
     * @throws IOException
     */
    public String readResponseVersion() throws IOException {
        String version = parseVersionToken();

        ensureLookahead(1, "HTTP status-line does not fit into the input buffer");
        int b = buffer[position] & 0xFF;
        if (b != SP) {
            throw new BadRequestException("HTTP-version in status-line must be followed by SP");
        }
        position++;

        return version;
    }

    /**
     * Reads the 3-digit status code from a response status-line, consuming the trailing SP.
     * Per RFC 9112 Section 4: status-code = 3DIGIT
     *
     * @return the status code as an integer (100–999)
     * @throws IOException
     */
    public int readStatusCode() throws IOException {
        // Need 3 digits + SP = 4 bytes
        ensureLookahead(4, "HTTP status-code does not fit into the input buffer");

        int d1 = buffer[position] & 0xFF;
        int d2 = buffer[position + 1] & 0xFF;
        int d3 = buffer[position + 2] & 0xFF;
        int sp = buffer[position + 3] & 0xFF;

        if (!isDigit(d1)) {
            throw new BadRequestException("Invalid status-code digit: 0x" + Integer.toHexString(d1));
        }
        if (!isDigit(d2)) {
            throw new BadRequestException("Invalid status-code digit: 0x" + Integer.toHexString(d2));
        }
        if (!isDigit(d3)) {
            throw new BadRequestException("Invalid status-code digit: 0x" + Integer.toHexString(d3));
        }
        if (sp != SP) {
            throw new BadRequestException("Status-code must be followed by SP");
        }

        position += 4;
        return (d1 - '0') * 100 + (d2 - '0') * 10 + (d3 - '0');
    }

    /**
     * Reads the optional reason-phrase from a response status-line, consuming the trailing CRLF.
     * Per RFC 9112 Section 4: reason-phrase = *( HTAB / SP / VCHAR / obs-text )
     *
     * @return the reason phrase (may be empty), with trailing whitespace trimmed
     * @throws IOException
     */
    public String readReasonPhrase() throws IOException {
        int tokenStart = position;
        int trailingWhitespace = 0;

        while (true) {
            // Fast path: scan buffered bytes directly
            while (position < limit) {
                int b = buffer[position] & 0xFF;

                if (b == CR || b == LF) {
                    int valueEnd = position - trailingWhitespace;
                    String reason = latin1String(tokenStart, valueEnd - tokenStart);
                    consumeLineEnding("HTTP status-line must end with CRLF");
                    return reason;
                }

                if (!HEADER_VALUE_OCTET[b]) {
                    throw new BadRequestException("Invalid octet in reason-phrase: 0x" + Integer.toHexString(b));
                }

                position++;
                if (b == SP || b == HTAB) {
                    trailingWhitespace++;
                } else {
                    trailingWhitespace = 0;
                }
            }

            // Slow path: need more data
            tokenStart = ensureTokenByte(tokenStart, "HTTP reason-phrase does not fit into the input buffer");
        }
    }

    private String parseVersionToken() throws IOException {
        // HTTP version is always exactly 8 bytes: HTTP/x.y
        ensureLookahead(8, "HTTP-version does not fit into the input buffer");

        int b0 = buffer[position] & 0xFF;
        int b1 = buffer[position + 1] & 0xFF;
        int b2 = buffer[position + 2] & 0xFF;
        int b3 = buffer[position + 3] & 0xFF;
        int b4 = buffer[position + 4] & 0xFF;
        int major = buffer[position + 5] & 0xFF;
        int b6 = buffer[position + 6] & 0xFF;
        int minor = buffer[position + 7] & 0xFF;

        if (b0 != 'H' || b1 != 'T' || b2 != 'T' || b3 != 'P' || b4 != '/' || b6 != '.' || major != '1') {
            throw new BadRequestException("Invalid HTTP-version");
        }

        position += 8;

        if (minor == '1')
            return HTTP_1_1;
        else if (minor == '0')
            return HTTP_1_0;

        throw new BadRequestException("Invalid HTTP version");
    }

    /**
     * reads the next http header from the stream
     *
     * @return the read header or null to indicate headers are done
     * @throws IOException
     */
    public Header readHeader() throws IOException {
        if (consumeEmptyLine()) {
            return null;
        }

        int nameStart = position;

        // Parse header field name
        while (true) {
            // Fast path: scan buffered bytes directly
            while (position < limit) {
                int b = buffer[position] & 0xFF;

                if (TCHAR[b]) {
                    position++;
                    continue;
                }

                if (b == COLON) {
                    if (position == nameStart) {
                        throw new BadRequestException("Empty header field name");
                    }
                    break;
                }

                if (b == SP || b == HTAB) {
                    throw new BadRequestException("Whitespace between header field name and colon is not allowed");
                }

                throw new BadRequestException("Invalid octet in header field name: 0x" + Integer.toHexString(b));
            }

            if (position < limit) {
                // broke out because we found COLON
                break;
            }

            // Slow path: need more data
            nameStart = ensureTokenByte(nameStart, "HTTP header line does not fit into the input buffer");
        }

        int nameLen = position - nameStart;
        String nameIn = asciiString(nameStart, nameLen);
        String nameLower = nameIn.toLowerCase(Locale.US);

        position++; // skip colon

        // Skip OWS after colon - fast path
        while (true) {
            while (position < limit) {
                int b = buffer[position] & 0xFF;
                if (b != SP && b != HTAB) {
                    break;
                }
                position++;
            }
            if (position < limit) {
                break;
            }
            ensureLookahead(1, "HTTP header line does not fit into the input buffer");
        }

        int valueStart = position;
        int trailingWhitespace = 0;

        // Parse header field value
        while (true) {
            // Fast path: scan buffered bytes directly
            while (position < limit) {
                int b = buffer[position] & 0xFF;

                if (b == CR || b == LF) {
                    int valueEnd = position - trailingWhitespace;
                    String value = latin1String(valueStart, valueEnd - valueStart);
                    consumeLineEnding("HTTP header line must end with CRLF");
                    return new Header(nameIn, nameLower, value);
                }

                if (!HEADER_VALUE_OCTET[b]) {
                    throw new BadRequestException("Invalid octet in header field value: 0x" + Integer.toHexString(b));
                }

                position++;
                if (b == SP || b == HTAB) {
                    trailingWhitespace++;
                } else {
                    trailingWhitespace = 0;
                }
            }

            // Slow path: need more data
            valueStart = ensureTokenByte(valueStart, "HTTP header line does not fit into the input buffer");
        }
    }

    /**
     * Reads the next chunk from a chunked Transfer-Encoding message body.
     * Per RFC 9112 Section 7.1:
     * <p>
     * chunked-body = *chunk last-chunk trailer-section CRLF
     * chunk        = chunk-size [ chunk-ext ] CRLF chunk-data CRLF
     * last-chunk   = 1*"0" [ chunk-ext ] CRLF
     * chunk-size   = 1*HEXDIG
     *
     * @return an {@link Chunk}; call {@link Chunk#isLast()} to detect
     * the terminal chunk. After the terminal chunk, no further
     * {@code readChunk()} calls should be made on this connection
     * unless it is reused for a new request.
     * @throws BadRequestException if the chunk framing is malformed.
     * @throws IOException         on I/O errors.
     */
    public Chunk readChunk(int maxSize) throws IOException {
        if (maxSize < 0) {
            throw new IllegalArgumentException("maxSize must be >= 0");
        }

        // Parse chunk-size: 1*HEXDIG directly from buffer
        long chunkSize = 0;
        int digitCount = 0;

        hexLoop:
        while (true) {
            while (position < limit) {
                int digit = hexDigitValue(buffer[position] & 0xFF);
                if (digit < 0) {
                    break hexLoop;
                }
                if (chunkSize > (Long.MAX_VALUE - digit) / 16L) {
                    throw new BadRequestException("Chunk size overflow");
                }
                chunkSize = (chunkSize * 16L) + digit;
                digitCount++;
                if (digitCount > 16) {
                    throw new BadRequestException("Chunk size field too long");
                }
                position++;
            }
            ensureLookahead(1, "Chunk header line does not fit into the input buffer");
        }

        if (digitCount == 0) {
            throw new BadRequestException("Missing chunk-size");
        }

        // Parse optional chunk extensions, then consume CRLF
        Map<String, String> extensions = readChunkExtensions();

        if (chunkSize == 0) {
            List<Header> trailers = new ArrayList<>();
            while (true) {
                Header header = readHeader();
                if (header == null) {
                    break;
                }
                trailers.add(header);
            }
            return new Chunk(new byte[0], extensions, trailers);
        }

        if (chunkSize > maxSize) {
            throw new BadRequestException("Chunk size too large: " + chunkSize);
        }

        byte[] data = new byte[(int) chunkSize];
        readFully(data);
        consumeLineEnding("Chunk data must be followed by CRLF");

        return new Chunk(data, extensions, Collections.emptyList());
    }

    @Override
    public int read() throws IOException {
        if (position >= limit) {
            int read = refill();
            if (read == -1) {
                return -1;
            }
        }

        return buffer[position++] & 0xFF;
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        if (off < 0 || len < 0 || len > bytes.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        int total = 0;

        while (len > 0) {
            int buffered = limit - position;
            if (buffered > 0) {
                int copy = Math.min(buffered, len);
                System.arraycopy(buffer, position, bytes, off, copy);
                position += copy;
                off += copy;
                len -= copy;
                total += copy;

                if (len == 0) {
                    return total;
                }

                continue;
            }

            if (total > 0 && in.available() == 0) {
                return total;
            }

            if (len >= buffer.length) {
                int read = in.read(bytes, off, len);
                if (read == -1) {
                    return total == 0 ? -1 : total;
                }
                total += read;
                return total;
            }

            int read = refill();
            if (read == -1) {
                return total == 0 ? -1 : total;
            }
        }

        return total;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }

        long skipped = 0;
        int buffered = limit - position;

        if (buffered > 0) {
            int consumed = (int) Math.min(n, buffered);
            position += consumed;
            skipped += consumed;
            n -= consumed;
        }

        if (n > 0) {
            skipped += in.skip(n);
        }

        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (limit - position) + in.available();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public synchronized void mark(int readlimit) {
    }

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void skipLeadingEmptyLines() throws IOException {
        while (true) {
            ensureLookahead(1, "HTTP request line does not fit into the input buffer");
            int b = buffer[position] & 0xFF;

            if (b == LF) {
                position++;
                continue;
            }

            if (b != CR) {
                return;
            }

            ensureLookahead(2, "HTTP request line does not fit into the input buffer");
            if ((buffer[position + 1] & 0xFF) != LF) {
                throw new BadRequestException("Invalid bare CR before request-line");
            }

            position += 2;
        }
    }

    private boolean consumeEmptyLine() throws IOException {
        int avail = limit - position;

        // Fast path: enough bytes buffered
        if (avail >= 2) {
            int b = buffer[position] & 0xFF;
            if (b == LF) {
                position++;
                return true;
            }
            if (b == CR) {
                if ((buffer[position + 1] & 0xFF) != LF) {
                    throw new BadRequestException("Invalid bare CR in header section");
                }
                position += 2;
                return true;
            }
            return false;
        }

        // Slow path: may need to read more
        ensureLookahead(1, "HTTP header line does not fit into the input buffer");
        int b = buffer[position] & 0xFF;

        if (b == LF) {
            position++;
            return true;
        }

        if (b != CR) {
            return false;
        }

        ensureLookahead(2, "HTTP header line does not fit into the input buffer");
        if ((buffer[position + 1] & 0xFF) != LF) {
            throw new BadRequestException("Invalid bare CR in header section");
        }

        position += 2;
        return true;
    }

    private void consumeLineEnding(String message) throws IOException {
        int avail = limit - position;

        // Fast path: enough bytes buffered to check without ensureLookahead
        if (avail >= 2) {
            int b = buffer[position] & 0xFF;
            if (b == LF) {
                position++;
                return;
            }
            if (b == CR) {
                if ((buffer[position + 1] & 0xFF) != LF) {
                    throw new BadRequestException("Invalid bare CR in HTTP line ending");
                }
                position += 2;
                return;
            }
            throw new BadRequestException(message + ": expected CRLF or LF");
        }

        // Slow path: may need to read more
        ensureLookahead(1, "HTTP line ending does not fit into the input buffer");
        int b = buffer[position] & 0xFF;

        if (b == LF) {
            position++;
            return;
        }

        if (b != CR) {
            throw new BadRequestException(message + ": expected CRLF or LF");
        }

        ensureLookahead(2, "HTTP line ending does not fit into the input buffer");
        if ((buffer[position + 1] & 0xFF) != LF) {
            throw new BadRequestException("Invalid bare CR in HTTP line ending");
        }

        position += 2;
    }

    private void ensureLookahead(int needed, String tooLongMessage) throws IOException {
        while (limit - position < needed) {
            readMore(position, tooLongMessage);
        }
    }

    private int ensureTokenByte(int tokenStart, String tooLongMessage) throws IOException {
        while (position >= limit) {
            tokenStart = readMore(tokenStart, tooLongMessage);
        }
        return tokenStart;
    }

    private int readMore(int preserveFrom, String tooLongMessage) throws IOException {
        if (preserveFrom < 0 || preserveFrom == limit) {
            position = 0;
            limit = 0;
            preserveFrom = 0;
        } else if (preserveFrom > 0) {
            int remaining = limit - preserveFrom;
            System.arraycopy(buffer, preserveFrom, buffer, 0, remaining);
            position -= preserveFrom;
            limit = remaining;
            preserveFrom = 0;
        } else if (limit == buffer.length) {
            throw new BadRequestException(tooLongMessage);
        }

        int read = in.read(buffer, limit, buffer.length - limit);
        if (read == -1) {
            throw new EOFException("Unexpected end of stream");
        }
        if (read == 0) {
            throw new IOException("InputStream returned 0 bytes while filling the buffer");
        }

        limit += read;
        return preserveFrom;
    }

    private int refill() throws IOException {
        if (position < limit) {
            return limit - position;
        }

        position = 0;
        limit = 0;

        int read = in.read(buffer, 0, buffer.length);
        if (read > 0) {
            limit = read;
        }

        return read;
    }

    private void readFully(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = read(bytes, offset, bytes.length - offset);
            if (read == -1) {
                throw new EOFException("Unexpected end of stream reading chunk data");
            }
            offset += read;
        }
    }

    /**
     * Parses optional chunk extensions and consumes the trailing CRLF.
     * Called after the chunk-size hex digits have already been parsed.
     */
    private Map<String, String> readChunkExtensions() throws IOException {
        // Skip OWS before checking for extensions or line ending
        skipOWS();

        ensureLookahead(1, "Chunk header line does not fit into the input buffer");
        int b = buffer[position] & 0xFF;

        // Fast path: no extensions
        if (b == CR || b == LF) {
            consumeLineEnding("Chunk header line must end with CRLF");
            return Collections.emptyMap();
        }

        // Slow path: parse extensions
        Map<String, String> extensions = new LinkedHashMap<>();

        while (true) {
            if (b != ';') {
                throw new BadRequestException(
                        "Expected ';' or CRLF in chunk extension, got: 0x" + Integer.toHexString(b));
            }
            position++; // skip ';'

            skipOWS();

            // Parse extension name: 1*tchar
            int nameStart = position;
            while (true) {
                while (position < limit) {
                    if (!TCHAR[buffer[position] & 0xFF]) break;
                    position++;
                }
                if (position < limit) break;
                nameStart = ensureTokenByte(nameStart, "Chunk header line does not fit into the input buffer");
            }

            if (position == nameStart) {
                throw new BadRequestException("Empty chunk extension name");
            }
            String name = asciiString(nameStart, position - nameStart);

            skipOWS();

            String value = null;
            ensureLookahead(1, "Chunk header line does not fit into the input buffer");

            if ((buffer[position] & 0xFF) == '=') {
                position++; // skip '='
                skipOWS();

                ensureLookahead(1, "Invalid chunk extension value");

                if ((buffer[position] & 0xFF) == '"') {
                    position++; // skip opening '"'
                    value = readChunkQuotedString();
                } else {
                    // Token value: 1*tchar
                    int valueStart = position;
                    while (true) {
                        while (position < limit) {
                            if (!TCHAR[buffer[position] & 0xFF]) break;
                            position++;
                        }
                        if (position < limit) break;
                        valueStart = ensureTokenByte(valueStart, "Chunk header line does not fit into the input buffer");
                    }
                    if (position == valueStart) {
                        throw new BadRequestException("Invalid chunk extension value");
                    }
                    value = asciiString(valueStart, position - valueStart);
                }
            }

            extensions.put(name, value);

            skipOWS();

            ensureLookahead(1, "Chunk header line does not fit into the input buffer");
            b = buffer[position] & 0xFF;

            if (b == CR || b == LF) {
                consumeLineEnding("Chunk header line must end with CRLF");
                return extensions;
            }
        }
    }

    private void skipOWS() throws IOException {
        while (true) {
            while (position < limit) {
                int b = buffer[position] & 0xFF;
                if (b != SP && b != HTAB) return;
                position++;
            }
            ensureLookahead(1, "Chunk header line does not fit into the input buffer");
        }
    }

    private String readChunkQuotedString() throws IOException {
        StringBuilder quoted = new StringBuilder();

        while (true) {
            ensureLookahead(1, "Unterminated quoted-string in chunk extension");
            int ch = buffer[position] & 0xFF;
            position++;

            if (ch == '"') {
                return quoted.toString();
            }

            if (ch == '\\') {
                ensureLookahead(1, "Unterminated quoted-pair in chunk extension");
                int escaped = buffer[position] & 0xFF;
                position++;

                if (escaped != HTAB && escaped != SP && !isFieldVchar(escaped)) {
                    throw new BadRequestException(
                            "Invalid quoted-pair in chunk extension: 0x" + Integer.toHexString(escaped));
                }

                quoted.append((char) escaped);
                continue;
            }

            if (ch != HTAB && ch != SP && ch != 0x21
                    && (ch < 0x23 || ch > 0x5B)
                    && (ch < 0x5D || ch > 0x7E)
                    && (ch < 0x80 || ch > 0xFF)) {
                throw new BadRequestException(
                        "Invalid octet in chunk extension quoted-string: 0x" + Integer.toHexString(ch));
            }

            quoted.append((char) ch);
        }
    }

    private String asciiString(int start, int length) {
        return new String(buffer, start, length, StandardCharsets.US_ASCII);
    }

    private String latin1String(int start, int length) {
        return new String(buffer, start, length, StandardCharsets.ISO_8859_1);
    }

    public static boolean isTchar(int b) {
        return b >= 0 && b < 256 && TCHAR[b];
    }

    private static int hexDigitValue(int b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }

    private static boolean isDigit(int b) {
        return b >= '0' && b <= '9';
    }

    private static boolean isFieldVchar(int b) {
        return (b >= 0x21 && b <= 0x7E) || (b >= 0x80 && b <= 0xFF);
    }
}
