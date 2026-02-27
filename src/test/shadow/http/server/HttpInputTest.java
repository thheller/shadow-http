package shadow.http.server;

import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HttpInputTest {

    private HttpInput inputFor(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.US_ASCII);
        // BufferedInputStream supports mark/reset
        return new HttpInput(null, new BufferedInputStream(new ByteArrayInputStream(bytes)));
    }

    private HttpRequest parse(String raw) throws IOException {
        return inputFor(raw).readRequest();
    }

    // -------------------------------------------------------------------------
    // Happy-path tests
    // -------------------------------------------------------------------------

    @Test
    void simpleGetRequest() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        );
        assertEquals("GET", req.requestMethod);
        assertEquals("/", req.requestTarget);
        assertEquals("HTTP/1.1", req.requestVersion);
        assertEquals(1, req.requestHeadersInOrder.size());
        assertEquals("Host", req.requestHeadersInOrder.get(0).nameIn);
        assertEquals("host", req.requestHeadersInOrder.get(0).name);
        assertEquals("example.com", req.requestHeadersInOrder.get(0).value);
    }

    @Test
    void postRequestWithMultipleHeaders() throws IOException {
        HttpRequest req = parse(
                "POST /submit HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 42\r\n" +
                "\r\n"
        );
        assertEquals("POST", req.requestMethod);
        assertEquals("/submit", req.requestTarget);
        assertEquals(3, req.requestHeadersInOrder.size());
    }

    @Test
    void methodIsUpperCased() throws IOException {
        HttpRequest req = parse(
                "get / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        );
        assertEquals("GET", req.requestMethod);
    }

    @Test
    void http10RequestDoesNotRequireHost() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.0\r\n" +
                "\r\n"
        );
        assertEquals("HTTP/1.0", req.requestVersion);
        assertTrue(req.requestHeadersInOrder.isEmpty());
    }

    @Test
    void leadingCrlfAreSkipped() throws IOException {
        HttpRequest req = parse(
                "\r\n\r\n" +
                "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        );
        assertEquals("GET", req.requestMethod);
    }

    @Test
    void headerValueOwsIsStripped() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.1\r\n" +
                "Host:   example.com   \r\n" +
                "\r\n"
        );
        assertEquals("example.com", req.requestHeadersInOrder.get(0).value);
    }

    @Test
    void headerNameIsPreservedCaseButLowercaseAvailable() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.1\r\n" +
                "X-Custom-Header: value\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        );
        Header h = req.requestHeadersInOrder.get(0);
        assertEquals("X-Custom-Header", h.nameIn);
        assertEquals("x-custom-header", h.name);
    }

    @Test
    void bareLfRecognizedAsLineTerminator() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.1\n" +
                "Host: example.com\n" +
                "\n"
        );
        assertEquals("GET", req.requestMethod);
        assertEquals("example.com", req.requestHeadersInOrder.get(0).value);
    }

    @Test
    void obsFoldReplacedWithSingleSpace() throws IOException {
        HttpRequest req = parse(
                "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "X-Folded: first\r\n" +
                "  continuation\r\n" +
                "\r\n"
        );
        Header folded = req.requestHeadersInOrder.get(1);
        assertEquals("first continuation", folded.value);
    }

    @Test
    void complexRequestTarget() throws IOException {
        HttpRequest req = parse(
                "GET /path?query=1&other=2#frag HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        );
        assertEquals("/path?query=1&other=2#frag", req.requestTarget);
    }

    // -------------------------------------------------------------------------
    // Error / bad-request tests
    // -------------------------------------------------------------------------

    @Test
    void missingHostHeader11ThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / HTTP/1.1\r\n" +
                "\r\n"
        ));
    }

    @Test
    void duplicateHostHeaderThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Host: other.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void emptyMethodThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                " / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void emptyRequestTargetThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET  HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void invalidHttpVersionPrefixThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / XTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void invalidMajorVersionDigitThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / HTTP/X.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void whitespaceBetweenHeaderNameAndColonThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / HTTP/1.1\r\n" +
                "Host : example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void missingColonAfterHeaderNameThrowsBadRequest() {
        assertThrows(BadRequestException.class, () -> parse(
                "GET / HTTP/1.1\r\n" +
                "HostXexample.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void requestTargetTooLongThrowsBadRequest() {
        String longTarget = "/".repeat(8001);
        assertThrows(BadRequestException.class, () -> parse(
                "GET " + longTarget + " HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n"
        ));
    }

    @Test
    void unexpectedEofThrowsEofException() {
        assertThrows(EOFException.class, () -> parse("GET /"));
    }

    @Test
    void invalidOctetInRequestTargetThrowsBadRequest() {
        // 0x00 is a CTL, not valid in request-target
        assertThrows(BadRequestException.class, () -> {
            byte[] bytes = "GET /path\0end HTTP/1.1\r\nHost: example.com\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);
            new HttpInput(null, new BufferedInputStream(new ByteArrayInputStream(bytes))).readRequest();
        });
    }
}