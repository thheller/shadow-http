package shadow.http.server;

import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpInputTest {

    private static HttpInput httpInput(int bufferSize, String... chunks) {
        return new HttpInput(new ChunkingInputStream(false, chunks), bufferSize);
    }

    private static HttpInput httpInputWithUnavailableStream(int bufferSize, String... chunks) {
        return new HttpInput(new ChunkingInputStream(true, chunks), bufferSize);
    }

    @Test
    void parsesRequestLineAndHeadersAcrossSmallBuffer() throws IOException {
        HttpInput input = httpInput(
                32,
                "GET /hello/wo",
                "rld?x=1 HTTP/1",
                ".1\r\nHost: Ex",
                "ample.com\r\nX-",
                "Test:\t  some value \t\r",
                "\n\r\nbo",
                "dy"
        );

        assertEquals("GET", input.readMethod());
        assertEquals("/hello/world?x=1", input.readTarget());
        assertEquals("HTTP/1.1", input.readVersion());

        Header host = input.readHeader();
        assertEquals("Host", host.nameIn);
        assertEquals("host", host.name);
        assertEquals("Example.com", host.value);

        Header xTest = input.readHeader();
        assertEquals("X-Test", xTest.nameIn);
        assertEquals("x-test", xTest.name);
        assertEquals("some value", xTest.value);

        assertNull(input.readHeader());
        assertEquals("body", new String(input.readAllBytes(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void skipsLeadingEmptyLinesBeforeMethod() throws IOException {
        HttpInput input = httpInput(8, "\r", "\n\nGE", "T / HT", "TP/1.1\r", "\n\r\n");

        assertEquals("GET", input.readMethod());
        assertEquals("/", input.readTarget());
        assertEquals("HTTP/1.1", input.readVersion());
        assertNull(input.readHeader());
    }

    @Test
    void acceptsBareLfLineTerminators() throws IOException {
        HttpInput input = httpInput(
                32,
                "GET / H",
                "TTP/1.1\nHo",
                "st: example",
                ".com\n\n"
        );

        assertEquals("GET", input.readMethod());
        assertEquals("/", input.readTarget());
        assertEquals("HTTP/1.1", input.readVersion());

        Header host = input.readHeader();
        assertEquals("Host", host.nameIn);
        assertEquals("host", host.name);
        assertEquals("example.com", host.value);
        assertNull(input.readHeader());
    }

    @Test
    void preservesLatin1HeaderValues() throws IOException {
        HttpInput input = httpInput(
                8,
                "GET / HT",
                "TP/1.1\r\nX-",
                "Name: caf\u00e9",
                "\r\n\r\n"
        );

        input.readMethod();
        input.readTarget();
        input.readVersion();

        Header header = input.readHeader();
        assertEquals("caf\u00e9", header.value);
    }

    @Test
    void rejectsEmptyMethodToken() {
        HttpInput input = httpInput(8, " ", "/ HTTP/", "1.1\r\n\r\n");

        BadRequestException error = assertThrows(BadRequestException.class, input::readMethod);
        assertEquals("Empty method token", error.getMessage());
    }

    @Test
    void rejectsInvalidOctetInMethodToken() {
        HttpInput input = httpInput(8, "GE", "(T / HT", "TP/1.1\r\n\r\n");

        BadRequestException error = assertThrows(BadRequestException.class, input::readMethod);
        assertEquals("Invalid octet in method token: 0x28", error.getMessage());
    }

    @Test
    void rejectsEmptyRequestTarget() throws IOException {
        HttpInput input = httpInput(8, "GET ", " HTTP/", "1.1\r\n\r\n");
        input.readMethod();

        BadRequestException error = assertThrows(BadRequestException.class, input::readTarget);
        assertEquals("Empty request-target", error.getMessage());
    }

    @Test
    void rejectsControlCharacterInRequestTarget() throws IOException {
        HttpInput input = httpInput(8, "GET /bad", "\u007ftarget H", "TTP/1.1\r\n\r\n");
        input.readMethod();

        BadRequestException error = assertThrows(BadRequestException.class, input::readTarget);
        assertEquals("Invalid octet in request-target: 0x7f", error.getMessage());
    }

    @Test
    void rejectsInvalidHttpVersionPrefix() throws IOException {
        HttpInput input = httpInput(8, "GET / H", "TTX/1.1\r", "\n\r\n");
        input.readMethod();
        input.readTarget();

        BadRequestException error = assertThrows(BadRequestException.class, input::readVersion);
        assertEquals("Invalid HTTP-version: expected 'P' but got 0x58", error.getMessage());
    }

    @Test
    void rejectsWhitespaceBeforeHeaderColon() throws IOException {
        HttpInput input = httpInput(
                8,
                "GET / HT",
                "TP/1.1\r\nHo",
                "st : exa",
                "mple.com\r\n\r\n"
        );
        input.readMethod();
        input.readTarget();
        input.readVersion();

        BadRequestException error = assertThrows(BadRequestException.class, input::readHeader);
        assertEquals("Whitespace between header field name and colon is not allowed", error.getMessage());
    }

    @Test
    void rejectsBareCrBeforeRequestLine() {
        HttpInput input = httpInput(8, "\rG", "ET / HT", "TP/1.1\r\n\r\n");

        BadRequestException error = assertThrows(BadRequestException.class, input::readMethod);
        assertEquals("Invalid bare CR before request-line", error.getMessage());
    }

    @Test
    void rejectsTooLongHeaderLineForBuffer() throws IOException {
        HttpInput input = httpInput(
                8,
                "GET / HT",
                "TP/1.1\r\nVe",
                "ryLongHe",
                "ader: va",
                "lue\r\n\r\n"
        );
        input.readMethod();
        input.readTarget();
        input.readVersion();

        BadRequestException error = assertThrows(BadRequestException.class, input::readHeader);
        assertEquals("HTTP header line does not fit into the input buffer", error.getMessage());
    }

    @Test
    void throwsEofWhenRequestLineEndsUnexpectedly() throws IOException {
        HttpInput input = httpInput(8, "GET / HT", "TP/1", ".");
        input.readMethod();
        input.readTarget();

        EOFException error = assertThrows(EOFException.class, input::readVersion);
        assertEquals("Unexpected end of stream", error.getMessage());
    }

    @Test
    void readByteArrayReturnsBufferedBodyBeforePollingUnderlyingStream() throws IOException {
        HttpInput input = httpInputWithUnavailableStream(
                8,
                "GET / HT",
                "TP/1.1\r\n",
                "\r\nhell",
                "o wor",
                "ld"
        );
        input.readMethod();
        input.readTarget();
        input.readVersion();
        assertNull(input.readHeader());

        byte[] bytes = new byte[32];
        int read = input.read(bytes, 0, bytes.length);

        assertEquals(4, read);
        assertArrayEquals("hell".getBytes(StandardCharsets.ISO_8859_1), copy(bytes, read));

        int secondRead = input.read(bytes, 0, bytes.length);
        assertEquals(5, secondRead);
        assertArrayEquals("o wor".getBytes(StandardCharsets.ISO_8859_1), copy(bytes, secondRead));

        int thirdRead = input.read(bytes, 0, bytes.length);
        assertEquals(2, thirdRead);
        assertArrayEquals("ld".getBytes(StandardCharsets.ISO_8859_1), copy(bytes, thirdRead));
    }

    @Test
    void skipAndAvailableReflectRemainingBodyBytes() throws IOException {
        HttpInput input = httpInput(8, "GET / HT", "TP/1.1\r\n", "\r\nabc", "def");

        input.readMethod();
        input.readTarget();
        input.readVersion();
        assertNull(input.readHeader());

        assertEquals(6, input.available());
        assertEquals(2, input.skip(2));
        assertEquals(4, input.available());
        assertEquals('c', input.read());
        assertEquals("def", new String(input.readAllBytes(), StandardCharsets.ISO_8859_1));
        assertEquals(0, input.available());
        assertEquals(-1, input.read());
    }

    @Test
    void markResetIsNotSupported() throws IOException {
        HttpInput input = httpInput(8, "GET / HT", "TP/1.1\r\n", "\r\n");

        assertFalse(input.markSupported());
        assertThrows(IOException.class, input::reset);
    }

    @Test
    void readsChunkedBodyWithExtensionsAndTrailersAcrossSmallBuffers() throws IOException {
        HttpInput input = httpInput(
                16,
                "4;foo=",
                "bar\r\nWi",
                "ki\r\n5",
                "; baz =",
                " \"qux\"",
                "\r\npedi",
                "a\r\n0;",
                "done\r\n",
                "X-Trai",
                "ler: y",
                "es\r\nAn",
                "other:",
                " ok\r\n",
                "\r\n"
        );

        Chunk first = input.readChunk(16);
        assertFalse(first.isLast());
        assertArrayEquals("Wiki".getBytes(StandardCharsets.ISO_8859_1), first.data());
        assertEquals("bar", first.extensions().get("foo"));
        assertTrue(first.trailers().isEmpty());

        Chunk second = input.readChunk(16);
        assertFalse(second.isLast());
        assertArrayEquals("pedia".getBytes(StandardCharsets.ISO_8859_1), second.data());
        assertEquals("qux", second.extensions().get("baz"));
        assertTrue(second.trailers().isEmpty());

        Chunk last = input.readChunk(16);
        assertTrue(last.isLast());
        assertTrue(last.data().length == 0);
        assertNull(last.extensions().get("done"));
        assertEquals(2, last.trailers().size());
        assertEquals("x-trailer", last.trailers().get(0).name);
        assertEquals("yes", last.trailers().get(0).value);
        assertEquals("another", last.trailers().get(1).name);
        assertEquals("ok", last.trailers().get(1).value);
    }

    @Test
    void rejectsChunkLargerThanConfiguredMaximum() {
        HttpInput input = httpInput(8, "A\r\n", "0123456789\r\n");

        BadRequestException error = assertThrows(BadRequestException.class, () -> input.readChunk(9));
        assertEquals("Chunk size too large: 10", error.getMessage());
    }

    // --- Response status-line tests ---

    @Test
    void parsesResponseStatusLineWithReasonPhrase() throws IOException {
        HttpInput input = httpInput(
                32,
                "HTTP/1.1 200 OK\r\n",
                "Content-Type: text/plain\r\n",
                "\r\nbody"
        );

        assertEquals("HTTP/1.1", input.readResponseVersion());
        assertEquals(200, input.readStatusCode());
        assertEquals("OK", input.readReasonPhrase());

        Header ct = input.readHeader();
        assertEquals("content-type", ct.name);
        assertEquals("text/plain", ct.value);
        assertNull(input.readHeader());

        assertEquals("body", new String(input.readAllBytes(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void parsesResponseStatusLineWithEmptyReasonPhrase() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.1 204 \r\n\r\n");

        assertEquals("HTTP/1.1", input.readResponseVersion());
        assertEquals(204, input.readStatusCode());
        assertEquals("", input.readReasonPhrase());
        assertNull(input.readHeader());
    }

    @Test
    void parsesResponseStatusLineWithMultiWordReasonPhrase() throws IOException {
        HttpInput input = httpInput(
                32,
                "HTTP/1.1 404 Not Found\r\n\r\n"
        );

        assertEquals("HTTP/1.1", input.readResponseVersion());
        assertEquals(404, input.readStatusCode());
        assertEquals("Not Found", input.readReasonPhrase());
        assertNull(input.readHeader());
    }

    @Test
    void parsesResponseStatusLineAcrossSmallBuffers() throws IOException {
        HttpInput input = httpInput(
                16,
                "HTTP/1.",
                "1 200 O",
                "K\r\nHos",
                "t: exam",
                "ple.com",
                "\r\n\r\n"
        );

        assertEquals("HTTP/1.1", input.readResponseVersion());
        assertEquals(200, input.readStatusCode());
        assertEquals("OK", input.readReasonPhrase());

        Header host = input.readHeader();
        assertEquals("host", host.name);
        assertEquals("example.com", host.value);
        assertNull(input.readHeader());
    }

    @Test
    void parsesResponseStatusLineWithBareLf() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.1 200 OK\n\n");

        assertEquals("HTTP/1.1", input.readResponseVersion());
        assertEquals(200, input.readStatusCode());
        assertEquals("OK", input.readReasonPhrase());
        assertNull(input.readHeader());
    }

    @Test
    void parsesResponseWithHttp10Version() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.0 301 Moved Permanently\r\n\r\n");

        assertEquals("HTTP/1.0", input.readResponseVersion());
        assertEquals(301, input.readStatusCode());
        assertEquals("Moved Permanently", input.readReasonPhrase());
        assertNull(input.readHeader());
    }

    @Test
    void rejectsInvalidStatusCodeDigit() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.1 2x0 OK\r\n\r\n");
        input.readResponseVersion();

        BadRequestException error = assertThrows(BadRequestException.class, input::readStatusCode);
        assertEquals("Invalid status-code digit: 0x78", error.getMessage());
    }

    @Test
    void rejectsStatusCodeWithoutTrailingSpace() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.1 200\r\n\r\n");
        input.readResponseVersion();

        BadRequestException error = assertThrows(BadRequestException.class, input::readStatusCode);
        assertEquals("Status-code must be followed by SP", error.getMessage());
    }

    @Test
    void rejectsInvalidVersionInStatusLine() {
        HttpInput input = httpInput(32, "HTTZ/1.1 200 OK\r\n\r\n");

        BadRequestException error = assertThrows(BadRequestException.class, input::readResponseVersion);
        assertEquals("Invalid HTTP-version: expected 'P' but got 0x5a", error.getMessage());
    }

    @Test
    void reasonPhraseTrimsTrailingWhitespace() throws IOException {
        HttpInput input = httpInput(32, "HTTP/1.1 200 OK   \r\n\r\n");
        input.readResponseVersion();
        input.readStatusCode();

        assertEquals("OK", input.readReasonPhrase());
    }

    @Test
    void reasonPhrasePreservesLatin1Characters() throws IOException {
        HttpInput input = httpInput(
                16,
                "HTTP/1.1 200 caf",
                "\u00e9\r\n\r\n"
        );
        input.readResponseVersion();
        input.readStatusCode();

        assertEquals("caf\u00e9", input.readReasonPhrase());
    }

    private static byte[] copy(byte[] bytes, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(bytes, 0, copy, 0, length);
        return copy;
    }

    private static final class ChunkingInputStream extends InputStream {

        private final byte[][] chunks;
        private final boolean zeroAvailable;
        private int chunkIndex;
        private int chunkOffset;

        private ChunkingInputStream(boolean zeroAvailable, String... chunks) {
            this.zeroAvailable = zeroAvailable;
            this.chunks = new byte[chunks.length][];
            for (int i = 0; i < chunks.length; i++) {
                this.chunks[i] = chunks[i].getBytes(StandardCharsets.ISO_8859_1);
            }
        }

        @Override
        public int read() {
            advanceChunk();
            if (chunkIndex >= chunks.length) {
                return -1;
            }

            return chunks[chunkIndex][chunkOffset++] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int off, int len) {
            if (bytes == null) {
                throw new NullPointerException("bytes");
            }
            if (off < 0 || len < 0 || len > bytes.length - off) {
                throw new IndexOutOfBoundsException();
            }
            if (len == 0) {
                return 0;
            }

            advanceChunk();
            if (chunkIndex >= chunks.length) {
                return -1;
            }

            byte[] chunk = chunks[chunkIndex];
            int copy = Math.min(len, chunk.length - chunkOffset);
            System.arraycopy(chunk, chunkOffset, bytes, off, copy);
            chunkOffset += copy;
            return copy;
        }

        @Override
        public long skip(long n) {
            if (n <= 0) {
                return 0;
            }

            long skipped = 0;
            while (skipped < n) {
                advanceChunk();
                if (chunkIndex >= chunks.length) {
                    return skipped;
                }

                int remaining = chunks[chunkIndex].length - chunkOffset;
                int chunkSkip = (int) Math.min(n - skipped, remaining);
                chunkOffset += chunkSkip;
                skipped += chunkSkip;
            }

            return skipped;
        }

        @Override
        public int available() {
            if (zeroAvailable) {
                return 0;
            }

            int available = 0;
            for (int i = chunkIndex; i < chunks.length; i++) {
                if (i == chunkIndex) {
                    available += chunks[i].length - chunkOffset;
                } else {
                    available += chunks[i].length;
                }
            }
            return available;
        }

        private void advanceChunk() {
            while (chunkIndex < chunks.length && chunkOffset >= chunks[chunkIndex].length) {
                chunkIndex++;
                chunkOffset = 0;
            }
        }
    }
}