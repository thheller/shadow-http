package shadow.http.server;

import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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

    @Test
    void servesExistingResourceFromJar() throws IOException {
        String response = TestConnection.run(new ClasspathHandler("/clojure"),
                "GET /core.clj HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
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
                "PUT /Server.class HTTP/1.1\r\n" +
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
    void jarDirectoryServesOnlyItsIndex(@TempDir Path temp) throws IOException {
        Path jar = temp.resolve("site.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("site/"));
            out.putNextEntry(new JarEntry("site/empty/"));
            out.putNextEntry(new JarEntry("site/index.html"));
            out.write("index from jar".getBytes(StandardCharsets.UTF_8));
        }
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
            var handler = ClasspathHandler.forPrefix(loader, "/site");
            String index = TestConnection.run(handler, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(index.startsWith("HTTP/1.1 200 "), index);
            assertTrue(index.endsWith("\r\n\r\nindex from jar"), index);
            String empty = TestConnection.run(handler, "GET /empty/ HTTP/1.1\r\nHost: localhost\r\n\r\n");
            assertTrue(empty.startsWith("HTTP/1.1 404 "), empty);
        }
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

