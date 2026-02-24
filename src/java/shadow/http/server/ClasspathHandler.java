package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Serves files from a ClassLoader using a configurable prefix.
 *
 * Example: prefix "/public" maps the request URI "/foo.txt" to the classpath
 * resource "/public/foo.txt".
 */
public class ClasspathHandler implements HttpHandler {

    public static final ZoneId GMT = ZoneId.of("GMT");
    public static final DateTimeFormatter LAST_MODIFIED_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final ClassLoader classLoader;
    private final String prefix;
    private Server server;

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

    /** Convenience: use the context class loader of the calling thread. */
    public ClasspathHandler(String prefix) {
        this(Thread.currentThread().getContextClassLoader(), prefix);
    }

    // -----------------------------------------------------------------------
    // HttpHandler
    // -----------------------------------------------------------------------

    @Override
    public void addedToServer(Server server) {
        this.server = server;
    }

    @Override
    public void handle(HttpContext ctx, HttpRequest request) throws IOException {
        if (!"GET".equals(request.method)) {
            return;
        }

        String uri = request.target;

        if (!uri.startsWith("/")) {
            return;
        }

        // Strip query string
        int queryIdx = uri.indexOf("?");
        if (queryIdx != -1) {
            uri = uri.substring(0, queryIdx);
        }

        // Map URI → classpath resource path
        // Don't attempt a direct lookup for directory-like URIs (ending with '/') since
        // ClassLoader.getResource("public/") can return a valid URL pointing at a jar
        // directory entry, which is not a servable file.
        String resourcePath;
        URL url;

        if (uri.endsWith("/")) {
            resourcePath = prefix + uri + "index.html";
            url = findResource(resourcePath);
        } else {
            resourcePath = prefix + uri;
            url = findResource(resourcePath);

            if (url == null) {
                // Try index.html fallback for extensionless paths
                resourcePath = prefix + uri + "/index.html";
                url = findResource(resourcePath);
            }
        }

        if (url == null) {
            return;
        }

        URLConnection conn = url.openConnection();
        // don't cache filesystem lookups, fine to cache files from jars since they can't change
        conn.setUseCaches(!"file".equals(url.getProtocol()));

        long lastModifiedMillis = conn.getLastModified();
        String lastModified = lastModifiedMillis > 0
                ? LAST_MODIFIED_FORMATTER.format(
                        new java.util.Date(lastModifiedMillis).toInstant().atZone(GMT))
                : null;

        if (lastModified != null) {
            String ifModifiedSince = request.getHeaderValue("if-modified-since");
            if (lastModified.equals(ifModifiedSince)) {
                ctx.respond().setStatus(304).noContent();
                return;
            }
        }

        long contentLength = conn.getContentLengthLong();

        // Derive filename for MIME type lookup
        String filename = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        String mimeType = server.config.guessMimeType(filename);

        boolean compress = contentLength >= 850 && server.config.isCompressible(mimeType);

        HttpResponse response = ctx.respond()
                .setStatus(200)
                .setContentType(mimeType)
                .setFlushEveryChunk(false);

        if (compress) {
            response.setCompress(true);
            response.setChunked(true);
        } else {
            response.setCompress(false);
            if (contentLength > 0) {
                response.setContentLength(contentLength);
            }
        }

        response.setHeader("cache-control", "private, no-cache");
        if (lastModified != null) {
            response.setHeader("last-modified", lastModified);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream(), 16384)) {
            response.writeStream(in);
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



