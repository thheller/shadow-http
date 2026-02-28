package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class FileHandler implements HttpHandler {

    public static final ZoneId GMT = ZoneId.of("GMT");
    public static final DateTimeFormatter LAST_MODIFIED_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    final Path root;

    public FileHandler(Path root) {
        this.root = root;
    }

    public static boolean servableFile(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.isReadable(path) && !Files.isHidden(path);
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        // POST should never serve files right?
        if (!"GET".equals(request.requestMethod) && !"HEAD".equals(request.requestMethod)) {
            return;
        }

        // FIXME: should this all be case insensitive? probably not right?
        // only make sense for case insensitive file systems, otherwise foo.txt and FOO.txt might exist
        // but we could only serve one?
        String uri = request.requestTarget;

        if (!uri.startsWith("/")) {
            return;
        }

        int queryIdx = uri.indexOf("?");
        if (queryIdx != -1) {
            uri = uri.substring(0, queryIdx);
        }

        Path file = root.resolve(uri.substring(1)).normalize();

        // prevent path traversal attacks (e.g. /../../../etc/passwd)
        if (!file.startsWith(root)) {
            return;
        }

        if (Files.isDirectory(file)) {
            file = file.resolve("index.html");
        }

        // sanity check, might have been deleted and watcher hasn't updated yet
        if (!servableFile(file)) {
            return;
        }

        final Server server = request.exchange.connection.getServer();
        final FileTime lastModifiedTime = Files.getLastModifiedTime(file);
        final String lastModified = LAST_MODIFIED_FORMATTER.format(lastModifiedTime.toInstant().atZone(GMT));

        final String ifModifiedSince = request.getRequestHeaderValue("if-modified-since");
        if (lastModified.equals(ifModifiedSince)) {
            request.respondNoContent();
        } else {
            long size = Files.size(file);

            // FIXME: maybe support range requests?

            // FIXME: don't do this per request. cache in FileInfo
            String mimeType = server.config.guessMimeType(file.getFileName().toString());

            // FIXME: config option
            boolean compress = size >= 850 && server.config.isCompressible(mimeType);

            request.setResponseStatus(200);
            request.setResponseHeader("content-type", mimeType);

            if (compress) {
                request.autoCompress = true;
                request.autoChunk = true;
            } else {
                request.autoCompress = false;
                request.responseLength = size;
            }

            // FIXME: configurable caching options
            // this is soft-cache, allows using cache but forces client to check
            // replying with 304 as above, so we don't send body again
            // this isn't ideal, but this is not a production server and during
            // dev files may change often and we never want stale files (e.g. shadow-cljs JS outputs)
            request.setResponseHeader("cache-control", "private, no-cache");
            request.setResponseHeader("last-modified", lastModified);

            // HEAD requests get headers but not body
            if ("GET".equals(request.requestMethod)) {
                // using the outputBufferSize since we want to fill that asap, might as well do it all at once
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), server.config.outputBufferSize)) {
                    request.writeStream(in);
                }
            } else {
                request.skipBody();
            }
        }
    }

    public static FileHandler forPath(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("root dir does not exist");
        }

        return new FileHandler(root.normalize().toAbsolutePath());
    }

    public static FileHandler forPath(File file) throws IOException {
        return forPath(file.getAbsoluteFile().toPath());
    }

    public static FileHandler forPath(String base) throws IOException {
        return forPath(Paths.get(base));
    }

    public static FileHandler forPath(String base, String... more) throws IOException {
        return forPath(Paths.get(base, more));
    }
}
