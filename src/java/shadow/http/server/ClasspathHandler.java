package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Serves files from a ClassLoader using a configurable prefix.
 * <p>
 * Example: prefix "/public" maps the request URI "/foo.txt" to the classpath
 * resource "/public/foo.txt".
 */
public class ClasspathHandler implements HttpHandler {

    private final ClassLoader classLoader;
    private final String prefix;

    /**
     * @param classLoader the ClassLoader to load resources from
     * @param prefix      classpath prefix to prepend to request URIs, e.g. "/public"
     *                    (leading slash required, no trailing slash)
     */
    public ClasspathHandler(ClassLoader classLoader, String prefix) {
        if (!prefix.startsWith("/")) {
            throw new IllegalArgumentException("prefix must start with '/'");
        }
        // Normalise: remove trailing slash
        this.prefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        this.classLoader = classLoader;
    }

    /**
     * Convenience: use the context class loader of the calling thread.
     */
    public ClasspathHandler(String prefix) {
        this(Thread.currentThread().getContextClassLoader(), prefix);
    }

    // -----------------------------------------------------------------------
    // HttpHandler
    // -----------------------------------------------------------------------

    @Override
    public void handle(HttpRequest request) throws IOException {
        if (!"GET".equals(request.requestMethod) && !"HEAD".equals(request.requestMethod)) {
            return;
        }

        String uri = request.getRequestPath();

        if (!uri.startsWith("/")) {
            return;
        }

        uri = prefix + uri;

        URL url = findResource(uri);

        if (url == null) {
            return;
        }

        boolean useIndexFiles = request.getConfig().useIndexFiles;

        URLConnection conn = url.openConnection();

        if (conn instanceof JarURLConnection jarc) {
            conn.setUseCaches(true);

            String entryName = jarc.getEntryName();

            if (entryName == null) {
                // root of jar, not servable
                return;
            }

            // must not use try-with-resources here since that closes the jar file
            // which then later causes conn.getInputStream() to fail with zip file is closed
            JarFile jar = jarc.getJarFile();
            JarEntry direct = jar.getJarEntry(entryName);
            if (direct != null && direct.isDirectory()) {
                if (!useIndexFiles) {
                    // not serving directory
                    return;
                }

                URL index = findResource(uri + (uri.endsWith("/") ? "index.html" : "/index.html"));
                if (index == null) {
                    // no index, still not serving directory
                    return;
                }

                url = index;
                conn = index.openConnection();
            }

        } else if ("file".equals(url.getProtocol())) {
            conn.setUseCaches(false);

            try {
                Path path = Paths.get(url.toURI());
                if (Files.isDirectory(path)) {
                    if (!useIndexFiles) {
                        // not serving directory
                        return;
                    }

                    URL index = findResource(uri + (uri.endsWith("/") ? "index.html" : "/index.html"));
                    if (index == null) {
                        // no index, still not serving directory
                        return;
                    }

                    url = index;
                    conn = index.openConnection();
                }
            } catch (URISyntaxException e) {
            }
        } else {
            // only serving file and jar urls. not sure what else may be here
            return;
        }

        long lastModifiedMillis = conn.getLastModified();
        String lastModified = lastModifiedMillis > 0
                ? HttpRequest.DATE_FORMATTER.format(
                new java.util.Date(lastModifiedMillis).toInstant().atZone(HttpRequest.GMT))
                : null;

        if (lastModified != null) {
            String ifModifiedSince = request.getRequestHeaderValue("if-modified-since");
            if (lastModified.equals(ifModifiedSince)) {
                request.respondNoContent();
                return;
            }
        }

        final Server server = request.exchange.connection.getServer();
        final long contentLength = conn.getContentLengthLong();

        // Derive filename for MIME type lookup
        final String resourcePath = url.getPath();
        final String filename = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        final String mimeType = server.config.guessMimeType(filename);

        boolean compress = contentLength >= 850 && server.config.isCompressible(mimeType);

        request.setResponseStatus(200);
        request.setResponseHeader("content-type", mimeType);

        if (compress) {
            request.autoCompress = true;
            request.autoChunk = true;
        } else {
            request.autoCompress = false;
            request.responseLength = contentLength;
        }

        request.setResponseHeader("cache-control", "private, no-cache");
        if (lastModified != null) {
            request.setResponseHeader("last-modified", lastModified);
        }

        if ("GET".equals(request.requestMethod)) {
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), server.config.outputBufferSize)) {
                request.writeStream(in);
            }
        } else {
            request.skipBody();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Looks up a classpath resource, stripping the leading '/' as required by
     * {@link ClassLoader#getResource(String)}.
     */
    private URL findResource(String path) {
        // ClassLoader.getResource does not accept a leading '/'
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        return classLoader.getResource(stripped);
    }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    public static ClasspathHandler forPrefix(String prefix) {
        return new ClasspathHandler(prefix);
    }

    public static ClasspathHandler forPrefix(ClassLoader classLoader, String prefix) {
        return new ClasspathHandler(classLoader, prefix);
    }
}



