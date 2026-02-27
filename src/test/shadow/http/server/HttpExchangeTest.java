package shadow.http.server;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;


// FIXME: theses tests are very brittle since the actual order of response headers doesn't actually matter
// and may change. also casing of headers shouldn't assume its always lower case
// but I can't be bothered parsing them properly right now

public class HttpExchangeTest {

    public static String run(HttpHandler handler, String input) throws IOException {
        Server server = new Server();
        server.setHandler(handler);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        TestConnection con = new TestConnection(server, input, out);

        HttpExchange exchange = new HttpExchange(con);
        exchange.process();

        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void testWebSocketAcceptKey() throws NoSuchAlgorithmException {
        String result = HttpRequest.computeWebSocketAcceptKey("dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", result);
    }

    @Test
    void simpleGetRequest() throws IOException {
        HttpHandler helloWorld = (request) -> {
            request.writeString("Hello World!");
        };

        String result = run(helloWorld,
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 12\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "Hello World!", result);
    }

    @Test
    void simpleMultipleRequests() throws IOException {
        HttpHandler helloWorld = (request) -> {
            request.writeString("Hello World!");
        };

        String request = "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n";

        String result = run(helloWorld, request + request);

        String expectedResponse =
                "HTTP/1.1 200 \r\n" +
                        "content-length: 12\r\n" +
                        "connection: keep-alive\r\n" +
                        "\r\n" +
                        "Hello World!";

        assertEquals(expectedResponse + expectedResponse, result);
    }

    @Test
    void postRequestWithBody() throws IOException {
        HttpHandler echo = (request) -> {
            String body = new String(request.requestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.writeString("Echo: " + body);
        };

        String result = run(echo,
                "POST /submit HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Content-Length: 11\r\n" +
                        "\r\n" +
                        "hello=world"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 17\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "Echo: hello=world", result);
    }

    @Test
    void postRequestWithBodyThatsNotHandled() throws IOException {
        HttpHandler echo = (request) -> {
            request.writeString("totally ignored body");
        };

        String result = run(echo,
                "POST /submit HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Content-Length: 11\r\n" +
                        "\r\n" +
                        "hello=world" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 20\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "totally ignored body" +
                "HTTP/1.1 200 \r\n" +
                "content-length: 20\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "totally ignored body", result);
    }

    @Test
    void postRequestWithChunkedBody() throws IOException {
        // Build a chunked-encoded request body: two data chunks + terminal chunk
        String chunk1 = "x".repeat(4096);
        String chunk2 = "y".repeat(4096);
        String chunkedBody =
                Integer.toHexString(chunk1.length()) + "\r\n" + chunk1 + "\r\n" +
                        Integer.toHexString(chunk2.length()) + "\r\n" + chunk2 + "\r\n" +
                        "0\r\n\r\n";

        HttpHandler echo = (request) -> {
            String body = new String(request.requestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.writeString(body);
        };

        String result = run(echo,
                "POST /upload HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        chunkedBody
        );

        String expectedBody = chunk1 + chunk2;
        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: " + expectedBody.length() + "\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                expectedBody, result);
    }

    @Test
    void largeBody() throws IOException {
        String largePayload = "x".repeat(10000);

        HttpHandler echo = (request) -> {
            String body = new String(request.requestBody().readAllBytes(), StandardCharsets.UTF_8);
            request.writeString(body);
        };

        String result = run(echo,
                "POST /upload HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Content-Length: 10000\r\n" +
                        "\r\n" +
                        largePayload
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 10000\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                largePayload, result);
    }

    @Test
    void notFoundWhenUnhandled() throws IOException {
        HttpHandler neverHandles = (request) -> {
        };

        String result = run(neverHandles,
                "GET /missing HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 404 \r\n" +
                "content-type: text/plain\r\n" +
                "content-length: 10\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "Not found.", result);
    }

    @Test
    void customStatusCode() throws IOException {
        HttpHandler handler = (request) -> {
            request.setResponseStatus(201).writeString("Created");
        };

        String result = run(handler,
                "POST /items HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 201 \r\n" +
                "content-length: 7\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "Created", result);
    }

    @Test
    void customContentType() throws IOException {
        HttpHandler handler = (request) -> {
            request.setResponseHeader("content-type", "application/json").writeString("{\"ok\":true}");
        };

        String result = run(handler,
                "GET /api HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-type: application/json\r\n" +
                "content-length: 11\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "{\"ok\":true}", result);
    }

    @Test
    void customResponseHeader() throws IOException {
        HttpHandler handler = (request) -> {
            request.setResponseHeader("x-custom", "foobar").writeString("ok");
        };

        String result = run(handler,
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "x-custom: foobar\r\n" +
                "content-length: 2\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "ok", result);
    }

    @Test
    void requestHeadersAreAccessible() throws IOException {
        HttpHandler handler = (request) -> {
            String ua = request.getRequestHeaderValue("user-agent");
            request.writeString("UA: " + ua);
        };

        String result = run(handler,
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "User-Agent: TestBrowser/1.0\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 19\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "UA: TestBrowser/1.0", result);
    }

    @Test
    void requestPathAndMethodAccessible() throws IOException {
        HttpHandler handler = (request) -> {
            request.writeString(request.requestMethod + " " + request.requestTarget);
        };

        String result = run(handler,
                "DELETE /items/42 HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 16\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "DELETE /items/42", result);
    }

    @Test
    void emptyBodyResponse() throws IOException {
        HttpHandler handler = (request) -> {
            request.setResponseStatus(204).setResponseHeader("content-length", "0").skipBody();
        };

        String result = run(handler,
                "DELETE /items/1 HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 204 \r\n" +
                "content-length: 0\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n", result);
    }

    @Test
    void doubleRespondThrows() throws IOException {
        HttpHandler handler = (request) -> {
            request.writeString("first");
            try {
                request.writeString("second");
            } catch (IllegalStateException e) {
                assertEquals("HttpRequest already completed", e.getMessage());
            }
        };

        run(handler,
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );
    }

    @Test
    void emptyInputProducesNoOutput() throws IOException {
        HttpHandler handler = (request) -> {
            request.writeString("should not happen");
        };

        String result = run(handler, "");

        assertEquals("", result);
    }

    @Test
    void requestWithQueryString() throws IOException {
        HttpHandler handler = (request) -> {
            request.writeString("path=" + request.requestTarget);
        };

        String result = run(handler,
                "GET /search?q=hello&page=1 HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n"
        );

        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 27\r\n" +
                "connection: keep-alive\r\n" +
                "\r\n" +
                "path=/search?q=hello&page=1", result);
    }


    /*
    @Test
    void multipleHeadersSameName() throws IOException {
        HttpHandler handler = (request)-> {
            String accept = request.getHeaderValue("accept");
            request.respond().writeString("accept=" + accept);
            return true;
        };

        String result = run(handler,
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "Accept: text/html\r\n" +
                        "Accept: application/json\r\n" +
                        "\r\n"
        );

        // claude getting lazy in writing asserts
        assert result.contains("accept=");
    }

     */

    @Test
    void connectionCloseHeader() throws IOException {
        HttpHandler handler = (request) -> {
            request.setCloseAfter(true).writeString("bye");
        };

        // Send two requests but expect only one response due to connection: close
        String twoRequests =
                "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n" +
                        "GET / HTTP/1.1\r\n" +
                        "Host: example.com\r\n" +
                        "\r\n";

        String result = run(handler, twoRequests);

        // Should only contain one response since connection: close breaks the loop
        assertEquals("HTTP/1.1 200 \r\n" +
                "content-length: 3\r\n" +
                "connection: close\r\n" +
                "\r\n" +
                "bye", result);
    }

    @Test
    void badRequestGets400() throws IOException {
        HttpHandler handler = (request) -> {
            throw new IllegalStateException("should not have gotten here");
        };

        // request missing Host header
        String noHost =
                "GET / HTTP/1.1\r\n" +
                        "\r\n";

        String result = run(handler, noHost);

        assertEquals("HTTP/1.1 400 \r\n" +
                "content-type: text/plain\r\n" +
                "content-length: 54\r\n" +
                "connection: close\r\n" +
                "\r\n" +
                "Missing required Host header field in HTTP/1.1 request", result);
    }
}
