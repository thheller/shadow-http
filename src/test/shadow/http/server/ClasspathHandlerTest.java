package shadow.http.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class ClasspathHandlerTest {

    // The class file for Server is guaranteed to be on the classpath during tests.
    // With prefix "/shadow/http/server" the request URI "/Server.class" maps to
    // the classpath resource "shadow/http/server/Server.class".

    private static final String PREFIX = "/shadow/http/server";
    private static final ClassLoader CL = ClasspathHandlerTest.class.getClassLoader();


    private static ClasspathHandler handler() {
        return new ClasspathHandler(CL, PREFIX);
    }

    // -----------------------------------------------------------------------
    // Serving a known resource
    // -----------------------------------------------------------------------

    @Test
    void servesExistingResource() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /Server.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
        assertTrue(response.contains("content-type: application/octet-stream"));
        assertTrue(response.contains("cache-control: private, no-cache"));
    }

    // -----------------------------------------------------------------------
    // Missing resource → no response (404 comes from the server fallback)
    // -----------------------------------------------------------------------

    @Test
    void returns404ForMissingResource() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /DoesNotExist.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Non-GET methods are ignored
    // -----------------------------------------------------------------------

    @Test
    void ignoresNonGetMethods() throws IOException {
        String response = TestConnection.run(handler(),
                "POST /Server.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        // handler does nothing for POST → server returns 404
        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Query string is stripped before resource lookup
    // -----------------------------------------------------------------------

    @Test
    void stripsQueryString() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /Server.class?v=123 HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Directory URI (/) must not open a jar directory entry as a file
    // -----------------------------------------------------------------------

    @Test
    void directoryUriDoesNotServeJarDirectoryEntry() throws IOException {
        // "/" → prefix + "/" + "index.html" → "shadow/http/server/index.html" which doesn't exist
        String response = TestConnection.run(handler(),
                "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    @Test
    void trailingSlashUriDoesNotServeJarDirectoryEntry() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /subdir/ HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Conditional GET (If-Modified-Since)
    // -----------------------------------------------------------------------

    @Test
    void conditionalGetReturns304WhenNotModified() throws IOException {
        // First request – grab the Last-Modified value from the response
        String first = TestConnection.run(handler(),
                "GET /Server.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        // Only attempt conditional GET if the server actually sent a last-modified header.
        // For resources loaded from the filesystem (e.g. during a regular Maven build) the
        // timestamp is available; for entries inside a jar it may be zero and the header
        // is omitted – in that case we simply skip the 304 assertion.
        int lmIdx = first.toLowerCase().indexOf("last-modified: ");
        if (lmIdx == -1) {
            // No last-modified sent; nothing to assert
            return;
        }

        int lmEnd = first.indexOf("\r\n", lmIdx);
        String lastModifiedValue = first.substring(lmIdx + "last-modified: ".length(), lmEnd);

        String second = TestConnection.run(handler(),
                "GET /Server.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "If-Modified-Since: " + lastModifiedValue + "\r\n" +
                        "\r\n");

        assertTrue(second.startsWith("HTTP/1.1 304 "), "expected 304, got: " + second.substring(0, second.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Prefix normalisation
    // -----------------------------------------------------------------------

    @Test
    void trailingSlashInPrefixIsNormalised() throws IOException {
        // Prefix with trailing slash should behave identically to one without
        ClasspathHandler h = new ClasspathHandler(CL, PREFIX + "/");
        String response = TestConnection.run(h,
                "GET /Server.class HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
    }

    @Test
    void prefixWithoutLeadingSlashThrows() {
        assertThrows(IllegalArgumentException.class, () -> new ClasspathHandler(CL, "no-leading-slash"));
    }
}

