package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * A Stream based HTTP Parser following RFC 9112
 *
 * requires InputStream that supports mark, assuming we'll get one and don't check
 */
public class HttpInput {

    private static final int CR = '\r';
    private static final int LF = '\n';
    private static final int SP = ' ';
    private static final int HTAB = '\t';
    private static final int COLON = ':';

    // FIXME: should these be configurable?
    private static final int MAX_REQUEST_LINE_LENGTH = 8000;
    private static final int MAX_HEADER_NAME_LENGTH = 1024;
    private static final int MAX_HEADER_VALUE_LENGTH = 8192;

    // FIXME: do not go too high, this is already possibly almost 2mb of request header data
    // not sure anything valid will ever send that
    private static final int MAX_HEADERS = 200;

    private final InputStream in;
    private final StringBuilder buf = new StringBuilder(256);

    public HttpInput(InputStream in) {
        this.in = in;
    }

    /**
     * Reads and parses an HTTP/1.1 request message from the input stream,
     * returning the method, request-target, HTTP-version, and header fields.
     * <p>
     * Implements parsing per RFC 9112 Sections 2, 3, and 5.
     */
    public HttpRequest readRequest() throws IOException {
        // Section 2.2: ignore leading CRLF(s) before request-line
        skipLeadingCrlf();

        // Section 3: request-line = method SP request-target SP HTTP-version CRLF
        String method = readMethod();
        expectSp();
        String target = readTarget();
        expectSp();
        String version = readVersion();
        expectCrlf();

        HttpRequest request = new HttpRequest(method, target, version);

        // Section 5: header fields until empty line
        readHeaders(request);

        // Section 3.2: Host header validation
        validateHostHeader(request, version);

        return request;
    }

    /**
     * Reads the next chunk from a chunked Transfer-Encoding message body.
     * Per RFC 9112 Section 7.1:
     *
     *   chunked-body = *chunk last-chunk trailer-section CRLF
     *   chunk        = chunk-size [ chunk-ext ] CRLF chunk-data CRLF
     *   last-chunk   = 1*"0" [ chunk-ext ] CRLF
     *   chunk-size   = 1*HEXDIG
     *
     * @return an {@link Chunk}; call {@link Chunk#isLast()} to detect
     *         the terminal chunk. After the terminal chunk, no further
     *         {@code readChunk()} calls should be made on this connection
     *         unless it is reused for a new request.
     * @throws BadRequestException if the chunk framing is malformed.
     * @throws IOException on I/O errors.
     */
    public Chunk readChunk(int maxSize) throws IOException {
        // --- chunk-size ---
        long chunkSize = readChunkSize();

        // --- chunk-ext (optional) ---
        Map<String, String> extensions = readChunkExtensions();

        // --- CRLF after chunk-size [ chunk-ext ] ---
        expectCrlf();

        if (chunkSize == 0) {
            // Terminal chunk: read trailer-section then final CRLF.
            // trailer-section = *( field-line CRLF )
            List<Header> trailers = readTrailerSection();
            return new Chunk(new byte[0], extensions, trailers);
        }

        // Guard against absurdly large chunks to prevent OOM.
        if (chunkSize > maxSize) {
            throw new BadRequestException("Chunk size too large: " + chunkSize);
        }

        // --- chunk-data ---
        byte[] data = new byte[(int) chunkSize];
        int offset = 0;
        while (offset < data.length) {
            int read = in.read(data, offset, data.length - offset);
            if (read == -1) {
                throw new EOFException("Unexpected end of stream reading chunk data");
            }
            offset += read;
        }

        // --- CRLF after chunk-data ---
        expectCrlf();

        return new Chunk(data, extensions, Collections.emptyList());
    }

    /**
     * Reads 1*HEXDIG and returns the numeric chunk size.
     * Per RFC 9112 Section 7.1: recipients MUST anticipate potentially large
     * hexadecimal numerals and prevent integer conversion overflows.
     */
    private long readChunkSize() throws IOException {
        int digitCount = 0;
        long size = 0;
        while (true) {
            in.mark(1);
            int b = readByte();
            int digit = hexDigitValue(b);
            if (digit < 0) {
                // Not a hex digit — put it back and stop.
                in.reset();
                break;
            }
            digitCount++;
            if (digitCount > 16) {
                // 16 hex digits = 64-bit value; more would overflow.
                throw new BadRequestException("Chunk size field too long");
            }
            size = (size << 4) | digit;
        }
        if (digitCount == 0) {
            throw new BadRequestException("Missing chunk-size");
        }
        return size;
    }

    /**
     * Returns the numeric value of a hex digit byte, or -1 if not a hex digit.
     */
    private static int hexDigitValue(int b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }

    /**
     * Reads zero or more chunk extensions.
     * Per RFC 9112 Section 7.1.1:
     *
     *   chunk-ext      = *( BWS ";" BWS chunk-ext-name [ BWS "=" BWS chunk-ext-val ] )
     *   chunk-ext-name = token
     *   chunk-ext-val  = token / quoted-string
     *
     * Unrecognized extensions MUST be ignored per the RFC.
     * Returns a LinkedHashMap preserving insertion order.
     */
    private Map<String, String> readChunkExtensions() throws IOException {
        Map<String, String> extensions = new LinkedHashMap<>();

        while (true) {
            // Peek at the next byte.
            in.mark(1);
            int b = readByte();

            if (b == CR || b == LF) {
                // Start of CRLF terminating the chunk-size line — put back.
                in.reset();
                break;
            }

            if (b != ';') {
                throw new BadRequestException(
                    "Expected ';' or CRLF in chunk extension, got: 0x" + Integer.toHexString(b));
            }

            // Consume BWS before name.
            skipOws();

            // Read extension name (token).
            buf.setLength(0);
            while (true) {
                in.mark(1);
                int nb = readByte();
                if (isTchar(nb)) {
                    buf.append((char) nb);
                } else {
                    in.reset();
                    break;
                }
            }
            if (buf.length() == 0) {
                throw new BadRequestException("Empty chunk extension name");
            }
            String extName = buf.toString();

            // Optional: BWS "=" BWS chunk-ext-val
            in.mark(1);
            int eq = readByte();
            String extValue = null;
            if (eq == '=') {
                skipOws();
                in.mark(1);
                int firstVal = readByte();
                in.reset();
                if (firstVal == '"') {
                    extValue = readQuotedString();
                } else {
                    // token
                    buf.setLength(0);
                    while (true) {
                        in.mark(1);
                        int tb = readByte();
                        if (isTchar(tb)) {
                            buf.append((char) tb);
                        } else {
                            in.reset();
                            break;
                        }
                    }
                    extValue = buf.toString();
                }
            } else {
                // No '=', put back.
                in.reset();
            }

            // Per RFC: recipients MUST ignore unrecognized chunk extensions.
            // We still collect them so callers can inspect if desired.
            extensions.put(extName, extValue);
        }

        return extensions;
    }

    /**
     * Reads a quoted-string per RFC 9110 Section 5.6.4.
     * quoted-string = DQUOTE *( qdtext / quoted-pair ) DQUOTE
     * qdtext        = HTAB / SP / %x21 / %x23-5B / %x5D-7E / obs-text
     * quoted-pair   = "\" ( HTAB / SP / VCHAR / obs-text )
     */
    private String readQuotedString() throws IOException {
        int dquote = readByte();
        if (dquote != '"') {
            throw new BadRequestException("Expected '\"' starting quoted-string");
        }
        buf.setLength(0);
        while (true) {
            int b = readByte();
            if (b == '"') {
                break;
            } else if (b == '\\') {
                // quoted-pair
                int escaped = readByte();
                if (escaped != HTAB && escaped != SP && !isFieldVchar(escaped)) {
                    throw new BadRequestException(
                        "Invalid quoted-pair in quoted-string: 0x" + Integer.toHexString(escaped));
                }
                buf.append((char) escaped);
            } else if (b == HTAB || b == SP || b == 0x21
                    || (b >= 0x23 && b <= 0x5B)
                    || (b >= 0x5D && b <= 0x7E)
                    || (b >= 0x80 && b <= 0xFF)) {
                buf.append((char) b);
            } else {
                throw new BadRequestException(
                    "Invalid octet in quoted-string: 0x" + Integer.toHexString(b));
            }
            if (buf.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new BadRequestException("Chunk extension quoted-string too long");
            }
        }
        return buf.toString();
    }

    /**
     * Reads the trailer section of a chunked message and the terminating CRLF.
     * Per RFC 9112 Section 7.1.2:
     *
     *   trailer-section = *( field-line CRLF )
     *
     * Followed by a final empty CRLF (the blank line ending the chunked-body).
     */
    private List<Header> readTrailerSection() throws IOException {
        List<Header> trailers = new ArrayList<>();
        while (true) {
            // Peek: if next bytes are CRLF (or bare LF), it's the blank line.
            in.mark(2);
            int b = readByte();
            if (b == CR) {
                int next = readByte();
                if (next == LF) {
                    // Blank line — end of trailer section.
                    break;
                }
                in.reset();
            } else if (b == LF) {
                // Bare LF as blank line terminator (lenient per Section 2.2).
                break;
            } else {
                in.reset();
            }

            // Read a trailer field-line: field-name ":" OWS field-value OWS CRLF
            String name = readHeaderName();
            int colon = readByte();
            if (colon != ':') {
                throw new BadRequestException(
                    "Expected ':' after trailer field name, got: 0x" + Integer.toHexString(colon));
            }
            String value = readHeaderValue();
            trailers.add(new Header(name, name.toLowerCase(), value));
        }
        return trailers;
    }

    /**
     * Section 2.2: A server SHOULD ignore at least one empty line (CRLF)
     * received prior to the request-line.
     */
    private void skipLeadingCrlf() throws IOException {
        while (true) {
            in.mark(2);
            int b = readByte();
            if (b == CR) {
                int next = readByte();
                if (next == LF) {
                    continue; // consumed one CRLF, keep going
                }
                // not CRLF — push back both
                in.reset();
                return;
            } else if (b == LF) {
                // Section 2.2: MAY recognize bare LF as line terminator
                continue;
            } else {
                in.reset();
                return;
            }
        }
    }

    /**
     * Section 3.1: method = token
     * token = 1*tchar (RFC 9110 Section 5.6.2)
     * tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
     * "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
     */
    private String readMethod() throws IOException {
        buf.setLength(0);
        while (true) {
            in.mark(1);
            int b = readByte();
            if (isTchar(b)) {
                buf.append((char) b);
                if (buf.length() > MAX_REQUEST_LINE_LENGTH) {
                    throw new BadRequestException("Method token too long");
                }
            } else {
                in.reset();
                break;
            }
        }
        if (buf.length() == 0) {
            throw new BadRequestException("Empty method token");
        }
        return buf.toString().toUpperCase();
    }

    /**
     * Section 3.2: request-target — read until SP.
     * No whitespace is allowed in the request-target.
     */
    private String readTarget() throws IOException {
        buf.setLength(0);
        while (true) {
            in.mark(1);
            int b = readByte();
            if (b == SP || b == CR || b == LF) {
                in.reset();
                break;
            }
            if (b < 0x21 || b == 0x7F) {
                // CTLs and space are not allowed in request-target
                throw new BadRequestException("Invalid octet in request-target: 0x" + Integer.toHexString(b));
            }
            buf.append((char) b);
            if (buf.length() > MAX_REQUEST_LINE_LENGTH) {
                // Section 3: 414 URI Too Long
                throw new BadRequestException("Request-target too long");
            }
        }
        if (buf.length() == 0) {
            throw new BadRequestException("Empty request-target");
        }
        return buf.toString();
    }

    /**
     * Section 2.3: HTTP-version = HTTP-name "/" DIGIT "." DIGIT
     * HTTP-name = %s"HTTP" (case-sensitive)
     */
    private String readVersion() throws IOException {
        buf.setLength(0);
        // Read exactly "HTTP/"
        for (int i = 0; i < 5; i++) {
            int b = readByte();
            buf.append((char) b);
        }
        String prefix = buf.toString();
        if (!prefix.equals("HTTP/")) {
            throw new BadRequestException("Invalid HTTP-version prefix: " + prefix);
        }
        // DIGIT "." DIGIT
        int major = readByte();
        if (major < '0' || major > '9') {
            throw new BadRequestException("Invalid major version digit: " + (char) major);
        }
        buf.append((char) major);

        int dot = readByte();
        if (dot != '.') {
            throw new BadRequestException("Expected '.' in HTTP-version, got: " + (char) dot);
        }
        buf.append('.');

        int minor = readByte();
        if (minor < '0' || minor > '9') {
            throw new BadRequestException("Invalid minor version digit: " + (char) minor);
        }
        buf.append((char) minor);

        return buf.toString();
    }

    /**
     * Section 5: Read all header field lines until the empty line (CRLF CRLF).
     * field-line = field-name ":" OWS field-value OWS
     */
    private void readHeaders(HttpRequest request) throws IOException {
        int headerCount = 0;

        while (true) {
            // Check for the empty line that terminates the header section
            in.mark(2);
            int b = readByte();
            if (b == CR) {
                int next = readByte();
                if (next == LF) {
                    break; // end of header section
                }
                in.reset();
            } else if (b == LF) {
                // Section 2.2: MAY recognize bare LF
                break;
            } else {
                in.reset();
            }

            String nameIn = readHeaderName();
            String name = nameIn.toLowerCase();

            // Section 5.1: No whitespace between field name and colon
            int colon = readByte();
            if (colon != COLON) {
                throw new BadRequestException("Expected ':' after header field name, got: 0x" + Integer.toHexString(colon));
            }

            String value = readHeaderValue();

            request.headersInOrder.add(new Header(nameIn, name, value));
            request.headers.put(name, value);
            headerCount++;

            if (headerCount > MAX_HEADERS) {
                throw new BadRequestException(String.format("Client sent more than %d headers", MAX_HEADERS));

            }
        }
    }

    /**
     * Section 5: field-name = token (case-insensitive)
     * Section 5.1: No whitespace allowed between field name and colon.
     */
    private String readHeaderName() throws IOException {
        buf.setLength(0);
        while (true) {
            in.mark(1);
            int b = readByte();
            if (isTchar(b)) {
                buf.append((char) b);
                if (buf.length() > MAX_HEADER_NAME_LENGTH) {
                    throw new BadRequestException("Header field name too long");
                }
            } else {
                in.reset();
                break;
            }
        }
        if (buf.length() == 0) {
            throw new BadRequestException("Empty header field name");
        }

        // Section 5.1: reject if whitespace between name and colon
        in.mark(1);
        int next = readByte();
        in.reset();
        if (next == SP || next == HTAB) {
            throw new BadRequestException("Whitespace between header field name and colon is not allowed (400 Bad Request)");
        }

        return buf.toString();
    }

    /**
     * Section 5: Read field-value with leading/trailing OWS stripped.
     * field-value = *( field-content / obs-fold )
     * <p>
     * Section 5.2: obs-fold handling — replace with SP if encountered,
     * or reject. We replace per the MUST requirement for user agents.
     */
    private String readHeaderValue() throws IOException {
        // Skip leading OWS
        skipOws();

        buf.setLength(0);
        while (true) {
            in.mark(2);
            int b = readByte();

            if (b == CR) {
                int next = readByte();
                if (next == LF) {
                    // Check for obs-fold: CRLF followed by SP or HTAB
                    in.mark(1);
                    int afterLf = readByte();
                    if (afterLf == SP || afterLf == HTAB) {
                        // Section 5.2: replace obs-fold with SP
                        buf.append((char) SP);
                        skipOws(); // consume remaining OWS after fold
                        continue;
                    } else {
                        // End of this header field line
                        in.reset();
                        break;
                    }
                } else {
                    // bare CR — Section 2.2: replace with SP
                    buf.append((char) SP);
                    in.reset();
                    // re-read; the non-LF byte will be processed next iteration
                    // but we already consumed CR, so put back only the second byte
                    // Actually we need to handle this carefully:
                    // We read CR then non-LF. The CR becomes SP. Push back non-LF.
                    // in.reset() pushed back the non-LF byte already.
                    continue;
                }
            } else if (b == LF) {
                // bare LF — Section 2.2: MAY recognize as line terminator
                in.mark(1);
                int afterLf = readByte();
                if (afterLf == SP || afterLf == HTAB) {
                    // obs-fold with bare LF
                    buf.append((char) SP);
                    skipOws();
                    continue;
                } else {
                    in.reset();
                    break;
                }
            } else if (isFieldVchar(b) || b == SP || b == HTAB) {
                buf.append((char) b);
                if (buf.length() > MAX_HEADER_VALUE_LENGTH) {
                    throw new BadRequestException("Header field value too long");
                }
            } else {
                throw new BadRequestException("Invalid octet in header field value: 0x" + Integer.toHexString(b));
            }
        }

        // Strip trailing OWS from the accumulated value
        int end = buf.length();
        while (end > 0 && isOws(buf.charAt(end - 1))) {
            end--;
        }
        buf.setLength(end);

        return buf.toString();
    }

    /**
     * Section 3.2: A client MUST send a Host header field in all HTTP/1.1
     * request messages. A server MUST respond with 400 if missing or duplicated.
     */
    private void validateHostHeader(HttpRequest request, String version) throws IOException {
        if (!"HTTP/1.1".equals(version)) {
            return; // Host requirement is specific to HTTP/1.1
        }
        int hostCount = 0;

        if (request.hasRepeatedHeaders()) {
            for (Header h : request.headersInOrder) {
                if (h.name.equals("host")) {
                    hostCount++;
                }
            }
            if (hostCount == 0) {
                throw new BadRequestException("Missing required Host header field in HTTP/1.1 request");
            }
            if (hostCount > 1) {
                throw new BadRequestException("Multiple Host header fields in HTTP/1.1 request");
            }
        } else if (!request.hasHeader("host")) {
            throw new BadRequestException("Missing required Host header field in HTTP/1.1 request");
        }
    }

    private void expectSp() throws IOException {
        int b = readByte();
        if (b != SP) {
            throw new BadRequestException("Expected SP, got: 0x" + Integer.toHexString(b));
        }
    }

    private void expectCrlf() throws IOException {
        int b = readByte();
        if (b == CR) {
            int next = readByte();
            if (next != LF) {
                throw new BadRequestException("Expected LF after CR, got: 0x" + Integer.toHexString(next));
            }
        } else if (b == LF) {
            // Section 2.2: MAY recognize bare LF as line terminator
        } else {
            throw new BadRequestException("Expected CRLF after HTTP-version, got: 0x" + Integer.toHexString(b));
        }
    }

    private void skipOws() throws IOException {
        while (true) {
            in.mark(1);
            int b = readByte();
            if (b == SP || b == HTAB) {
                continue;
            }
            in.reset();
            return;
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Unexpected end of stream");
        }
        return b;
    }

    /**
     * tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." /
     * "^" / "_" / "`" / "|" / "~" / DIGIT / ALPHA
     */
    private static boolean isTchar(int b) {
        if (b >= 'A' && b <= 'Z') return true;
        if (b >= 'a' && b <= 'z') return true;
        if (b >= '0' && b <= '9') return true;
        switch (b) {
            case '!':
            case '#':
            case '$':
            case '%':
            case '&':
            case '\'':
            case '*':
            case '+':
            case '-':
            case '.':
            case '^':
            case '_':
            case '`':
            case '|':
            case '~':
                return true;
            default:
                return false;
        }
    }

    /**
     * VCHAR = %x21-7E
     * obs-text = %x80-FF
     */
    private static boolean isFieldVchar(int b) {
        return (b >= 0x21 && b <= 0x7E) || (b >= 0x80 && b <= 0xFF);
    }

    private static boolean isOws(char c) {
        return c == SP || c == HTAB;
    }
}