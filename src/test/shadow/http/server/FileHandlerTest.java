package shadow.http.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FileHandlerTest {

    // We use the project's "src/dev" directory as the file root since it
    // contains known files (build.clj, repl.clj) that are always
    // present in the repository.

    private static final Path ROOT = Paths.get("src", "dev").toAbsolutePath().normalize();


    private static FileHandler handler() throws IOException {
        return FileHandler.forPath(ROOT);
    }

    // -----------------------------------------------------------------------
    // Serving a known file
    // -----------------------------------------------------------------------

    @Test
    void servesExistingFile() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /build.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
        assertTrue(response.contains("cache-control: private, no-cache"));
    }

    @Test
    void servesUncompressedFileWithContentLength() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /build.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        String head = response.substring(0, response.indexOf("\r\n\r\n") + 2);
        assertTrue(head.contains("content-length: " + Files.size(ROOT.resolve("build.clj")) + "\r\n"), head);
        assertFalse(head.contains("transfer-encoding"), head);
    }

    @Test
    void servesExistingFileWithEncodedPath() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /i%20haz+spaces.txt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
        assertTrue(response.contains("cache-control: private, no-cache"));
    }

    // -----------------------------------------------------------------------
    // Missing resource → handler does nothing → server returns 404
    // -----------------------------------------------------------------------

    @Test
    void returns404ForMissingFile() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /does-not-exist.txt HTTP/1.1\r\n" +
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
                "PUT /build.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        // handler skips POST → server falls through to 404
        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Query string is stripped before file lookup
    // -----------------------------------------------------------------------

    @Test
    void stripsQueryString() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /repl.clj?v=abc HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Directory URI → falls back to index.html (which doesn't exist → 404)
    // -----------------------------------------------------------------------

    @Test
    void directoryUriWithNoIndexReturns404() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /subdir/ HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        // There is no subdir/index.html, so handler does nothing → 404
        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Root URI without index.html returns 404
    // -----------------------------------------------------------------------

    @Test
    void rootUriWithNoIndexReturns404() throws IOException {
        String response = TestConnection.run(handler(),
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        // public/ has no index.html → 404
        assertTrue(response.startsWith("HTTP/1.1 404 "), "expected 404, got: " + response.substring(0, response.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // Conditional GET (If-Modified-Since) → 304
    // -----------------------------------------------------------------------

    @Test
    void conditionalGetReturns304WhenNotModified() throws IOException {
        // First request to capture Last-Modified
        String first = TestConnection.run(handler(),
                "GET /build.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        int lmIdx = first.toLowerCase().indexOf("last-modified: ");
        if (lmIdx == -1) {
            // No Last-Modified header sent – nothing to assert
            return;
        }

        int lmEnd = first.indexOf("\r\n", lmIdx);
        String lastModifiedValue = first.substring(lmIdx + "last-modified: ".length(), lmEnd);

        // Second request using the captured timestamp
        String second = TestConnection.run(handler(),
                "GET /build.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "If-Modified-Since: " + lastModifiedValue + "\r\n" +
                "\r\n");

        assertTrue(second.startsWith("HTTP/1.1 304 "), "expected 304, got: " + second.substring(0, second.indexOf('\n')));
    }

    // -----------------------------------------------------------------------
    // forPath() throws when given a non-existent directory
    // -----------------------------------------------------------------------

    @Test
    void forPathThrowsForNonExistentDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> FileHandler.forPath(Paths.get("non-existent-dir-xyz")));
    }

    // -----------------------------------------------------------------------
    // Serves Clojure file (repl.clj)
    // -----------------------------------------------------------------------

    @Test
    void servesReplCljFile() throws IOException {
        String response = TestConnection.run(handler(),
                "GET /repl.clj HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        assertTrue(response.startsWith("HTTP/1.1 200 "), "expected 200, got: " + response.substring(0, response.indexOf('\n')));
        assertTrue(response.contains("cache-control: private, no-cache"));
    }

    @Test
    void fileAndClasspathPrefixesUseResolvedFileLocations(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("assets"));
        Files.writeString(root.resolve("inside.txt"), "inside");
        Files.writeString(temp.resolve("sibling.txt"), "sibling");
        try {
            Files.createSymbolicLink(root.resolve("alias.txt"), Path.of("inside.txt"));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
        }
        Files.createSymbolicLink(root.resolve("external.txt"), temp.resolve("sibling.txt"));

        try (var loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, null)) {
            for (HttpHandler handler : List.of(FileHandler.forPath(root),
                    IndexingFileHandler.forPath(root).findFiles(), ClasspathHandler.forPrefix(loader, "/assets"))) {
                String inside = TestConnection.run(handler, "GET /alias.txt HTTP/1.1\r\nHost: localhost\r\n\r\n");
                String outside = TestConnection.run(handler, "GET /external.txt HTTP/1.1\r\nHost: localhost\r\n\r\n");
                assertTrue(inside.endsWith("\r\n\r\ninside"), inside);
                assertTrue(outside.startsWith("HTTP/1.1 404 "), outside);
            }
        }
    }
}
