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
import java.util.Arrays;
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
        if (!FileHandler.supportedRequestType(request)) {
            return;
        }

        String uri = request.getRequestPath();

        if (!uri.startsWith("/") || uri.indexOf('\\') >= 0 || uri.indexOf('\0') >= 0
                || Arrays.asList(uri.split("/")).contains("..")) {
            return;
        }

        uri = prefix + uri;

        URL url = findResource(uri);

        if (url == null) {
            return;
        }

        boolean useIndexFiles = request.getConfig().useIndexFiles;

        String filename = uri.substring(uri.lastIndexOf('/') + 1);

        // Serve file: classpath entries directly via Path to avoid FileURLConnection
        // opening an unclosed FileInputStream on 304 / HEAD requests.
        if ("file".equals(url.getProtocol())) {
            try {
                Path path = Paths.get(url.toURI());
                if (Files.isDirectory(path)) {
                    if (!useIndexFiles) {
                        return;
                    }

                    URL index = findResource(uri + (uri.endsWith("/") ? "index.html" : "/index.html"));
                    if (index == null) {
                        return;
                    }

                    filename = "index.html";
                    if ("file".equals(index.getProtocol())) {
                        path = Paths.get(index.toURI());
                    } else {
                        serveJarUrl(request, index, filename, false);
                        return;
                    }
                }

                if (Files.isRegularFile(path) && Files.isReadable(path)) {
                    request.serveFile(path, filename);
                }
            } catch (URISyntaxException ignored) {
            }
            return;
        }

        if ("jar".equals(url.getProtocol())) {
            serveJarUrl(request, url, filename, useIndexFiles);
        }
    }

    private void serveJarUrl(HttpRequest request, URL url, String filename, boolean useIndexFiles) throws IOException {
        URLConnection conn = url.openConnection();
        if (!(conn instanceof JarURLConnection jarc)) {
            return;
        }

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
                return;
            }

            String indexEntry = entryName.endsWith("/") ? entryName + "index.html" : entryName + "/index.html";
            URL index = findResource("/" + indexEntry);
            if (index == null) {
                return;
            }

            filename = "index.html";
            if ("file".equals(index.getProtocol())) {
                try {
                    Path path = Paths.get(index.toURI());
                    if (Files.isRegularFile(path) && Files.isReadable(path)) {
                        request.serveFile(path, filename);
                    }
                } catch (URISyntaxException ignored) {
                }
                return;
            }

            conn = index.openConnection();
            if (!(conn instanceof JarURLConnection indexJarc)) {
                return;
            }
            conn.setUseCaches(true);
            String resolvedEntry = indexJarc.getEntryName();
            if (resolvedEntry == null) {
                return;
            }
            JarEntry idxDirect = indexJarc.getJarFile().getJarEntry(resolvedEntry);
            if (idxDirect != null && idxDirect.isDirectory()) {
                return;
            }
        }

        long lastModifiedMillis = conn.getLastModified();
        String lastModified = lastModifiedMillis > 0
                ? HttpRequest.DATE_FORMATTER.format(
                new java.util.Date(lastModifiedMillis).toInstant().atZone(HttpRequest.GMT))
                : null;

        if (lastModified != null) {
            String ifModifiedSince = request.getRequestHeaderValue("if-modified-since");
            if (lastModified.equals(ifModifiedSince)) {
                request.respondNotModified();
                return;
            }
        }

        final Server server = request.exchange.connection.getServer();
        final long contentLength = conn.getContentLengthLong();

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

        if ("HEAD".equals(request.requestMethod)) {
            request.skipBody();
        } else {
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), server.config.outputBufferSize)) {
                request.writeStream(in);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Looks up a classpath resource, stripping the leading '/' as required by
     * {@link ClassLoader#getResource(String)}.
     */
    private URL findResource(String path) throws IOException {
        // ClassLoader.getResource does not accept a leading '/'
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        URL url = classLoader.getResource(stripped);
        if (url == null || !"file".equals(url.getProtocol())) return url;

        // A class loader can have several directory entries containing this prefix.
        try {
            Path file = Paths.get(url.toURI()).toRealPath();
            String rootName = prefix.isEmpty() ? "" : prefix.substring(1) + "/";
            var roots = classLoader.getResources(rootName);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol()) && file.startsWith(Paths.get(root.toURI()).toRealPath())) {
                    return file.toUri().toURL();
                }
            }
            return null;
        } catch (URISyntaxException e) {
            throw new IOException("Invalid classpath file URL", e);
        }
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



