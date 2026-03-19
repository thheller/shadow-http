package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HttpInput extends InputStream {

    private static final int CR = '\r';
    private static final int LF = '\n';
    private static final int SP = ' ';
    private static final int HTAB = '\t';
    private static final int COLON = ':';

    private static final boolean[] TCHAR = new boolean[256];

    static {
        for (char c = 'A'; c <= 'Z'; c++) TCHAR[c] = true;
        for (char c = 'a'; c <= 'z'; c++) TCHAR[c] = true;
        for (char c = '0'; c <= '9'; c++) TCHAR[c] = true;
        for (char c : new char[]{'!', '#', '$', '%', '&', '\'', '*', '+', '-', '.', '^', '_', '`', '|', '~'}) {
            TCHAR[c] = true;
        }
    }

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
            tokenStart = ensureTokenByte(tokenStart, "HTTP method does not fit into the input buffer");
            int b = buffer[position] & 0xFF;

            if (isTchar(b)) {
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
    }

    public String readTarget() throws IOException {
        int tokenStart = position;

        while (true) {
            tokenStart = ensureTokenByte(tokenStart, "HTTP request-target does not fit into the input buffer");
            int b = buffer[position] & 0xFF;

            if (b == SP) {
                if (position == tokenStart) {
                    throw new BadRequestException("Empty request-target");
                }

                String target = latin1String(tokenStart, position - tokenStart);
                position++;
                return target;
            }

            if (b <= 0x20 || b == 0x7F) {
                throw new BadRequestException("Invalid octet in request-target: 0x" + Integer.toHexString(b));
            }

            position++;
        }
    }

    /**
     * @return the string indicating the used http version (most likely "HTTP/1.1")
     * @throws IOException
     */
    public String readVersion() throws IOException {
        int tokenStart = position;

        tokenStart = expectTokenByte(tokenStart, 'H', "Invalid HTTP-version");
        tokenStart = expectTokenByte(tokenStart, 'T', "Invalid HTTP-version");
        tokenStart = expectTokenByte(tokenStart, 'T', "Invalid HTTP-version");
        tokenStart = expectTokenByte(tokenStart, 'P', "Invalid HTTP-version");
        tokenStart = expectTokenByte(tokenStart, '/', "Invalid HTTP-version");

        tokenStart = ensureTokenByte(tokenStart, "HTTP-version does not fit into the input buffer");
        int major = buffer[position] & 0xFF;
        if (!isDigit(major)) {
            throw new BadRequestException("Invalid HTTP-version major digit: 0x" + Integer.toHexString(major));
        }
        position++;

        tokenStart = expectTokenByte(tokenStart, '.', "Invalid HTTP-version");

        tokenStart = ensureTokenByte(tokenStart, "HTTP-version does not fit into the input buffer");
        int minor = buffer[position] & 0xFF;
        if (!isDigit(minor)) {
            throw new BadRequestException("Invalid HTTP-version minor digit: 0x" + Integer.toHexString(minor));
        }
        position++;

        String version = asciiString(tokenStart, position - tokenStart);
        consumeLineEnding("HTTP-version must be followed by CRLF");
        return version;
    }

    /**
     * reads the next http header from the stream
     * @return the read header or null to indicate headers are done
     * @throws IOException
     */
    public Header readHeader() throws IOException {
        if (consumeEmptyLine()) {
            return null;
        }

        int nameStart = position;

        while (true) {
            nameStart = ensureTokenByte(nameStart, "HTTP header line does not fit into the input buffer");
            int b = buffer[position] & 0xFF;

            if (isTchar(b)) {
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

        String nameIn = asciiString(nameStart, position - nameStart);
        position++;

        while (true) {
            ensureLookahead(1, "HTTP header line does not fit into the input buffer");
            int b = buffer[position] & 0xFF;
            if (b == SP || b == HTAB) {
                position++;
                continue;
            }
            break;
        }

        int valueStart = position;
        int trailingWhitespace = 0;

        while (true) {
            valueStart = ensureTokenByte(valueStart, "HTTP header line does not fit into the input buffer");
            int b = buffer[position] & 0xFF;

            if (b == CR || b == LF) {
                break;
            }

            if (!isHeaderValueOctet(b)) {
                throw new BadRequestException("Invalid octet in header field value: 0x" + Integer.toHexString(b));
            }

            position++;
            if (b == SP || b == HTAB) {
                trailingWhitespace++;
            } else {
                trailingWhitespace = 0;
            }
        }

        int valueEnd = position - trailingWhitespace;
        String value = latin1String(valueStart, valueEnd - valueStart);
        consumeLineEnding("HTTP header line must end with CRLF");

        return new Header(nameIn, nameIn.toLowerCase(Locale.ROOT), value);
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

        String chunkLine = readChunkLine();
        int length = chunkLine.length();
        int index = 0;
        int digitCount = 0;
        long chunkSize = 0;

        while (index < length) {
            int digit = hexDigitValue(chunkLine.charAt(index));
            if (digit < 0) {
                break;
            }

            if (chunkSize > (Long.MAX_VALUE - digit) / 16L) {
                throw new BadRequestException("Chunk size overflow");
            }

            chunkSize = (chunkSize * 16L) + digit;
            digitCount++;
            if (digitCount > 16) {
                throw new BadRequestException("Chunk size field too long");
            }

            index++;
        }

        if (digitCount == 0) {
            throw new BadRequestException("Missing chunk-size");
        }

        Map<String, String> extensions = new LinkedHashMap<>();

        while (true) {
            index = skipChunkWhitespace(chunkLine, index);
            if (index >= length) {
                break;
            }

            int delimiter = chunkLine.charAt(index);
            if (delimiter != ';') {
                throw new BadRequestException(
                        "Expected ';' or CRLF in chunk extension, got: 0x" + Integer.toHexString(delimiter));
            }

            index++;
            index = skipChunkWhitespace(chunkLine, index);

            int nameStart = index;
            while (index < length && isTchar(chunkLine.charAt(index))) {
                index++;
            }

            if (index == nameStart) {
                throw new BadRequestException("Empty chunk extension name");
            }

            String name = chunkLine.substring(nameStart, index);
            index = skipChunkWhitespace(chunkLine, index);

            String value = null;
            if (index < length && chunkLine.charAt(index) == '=') {
                index++;
                index = skipChunkWhitespace(chunkLine, index);

                if (index >= length) {
                    throw new BadRequestException("Invalid chunk extension value");
                }

                if (chunkLine.charAt(index) == '"') {
                    StringBuilder quoted = new StringBuilder();
                    index++;

                    while (true) {
                        if (index >= length) {
                            throw new BadRequestException("Unterminated quoted-string in chunk extension");
                        }

                        int ch = chunkLine.charAt(index++);
                        if (ch == '"') {
                            break;
                        }

                        if (ch == '\\') {
                            if (index >= length) {
                                throw new BadRequestException("Unterminated quoted-pair in chunk extension");
                            }

                            int escaped = chunkLine.charAt(index++);
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

                    value = quoted.toString();
                } else {
                    int valueStart = index;
                    while (index < length && isTchar(chunkLine.charAt(index))) {
                        index++;
                    }

                    if (index == valueStart) {
                        throw new BadRequestException("Invalid chunk extension value");
                    }

                    value = chunkLine.substring(valueStart, index);
                }
            }

            extensions.put(name, value);
        }

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

    private int expectTokenByte(int tokenStart, int expected, String message) throws IOException {
        tokenStart = ensureTokenByte(tokenStart, "HTTP token does not fit into the input buffer");
        int actual = buffer[position] & 0xFF;
        if (actual != expected) {
            throw new BadRequestException(message + ": expected '" + (char) expected + "' but got 0x" + Integer.toHexString(actual));
        }
        position++;
        return tokenStart;
    }

    private void consumeLineEnding(String message) throws IOException {
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

    private String readChunkLine() throws IOException {
        StringBuilder line = new StringBuilder();

        while (true) {
            int b = readRequiredByte("Unexpected end of stream reading chunk header");
            if (b == LF) {
                return line.toString();
            }

            if (b == CR) {
                int next = readRequiredByte("Unexpected end of stream reading chunk header");
                if (next != LF) {
                    throw new BadRequestException("Invalid bare CR in chunk header");
                }
                return line.toString();
            }

            line.append((char) b);
        }
    }

    private int readRequiredByte(String eofMessage) throws IOException {
        int b = read();
        if (b == -1) {
            throw new EOFException(eofMessage);
        }
        return b;
    }

    private int skipChunkWhitespace(String chunkLine, int index) {
        while (index < chunkLine.length()) {
            char ch = chunkLine.charAt(index);
            if (ch != SP && ch != HTAB) {
                break;
            }
            index++;
        }
        return index;
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

    private static boolean isHeaderValueOctet(int b) {
        return b == SP || b == HTAB || (b >= 0x21 && b <= 0x7E) || (b >= 0x80 && b <= 0xFF);
    }
}
