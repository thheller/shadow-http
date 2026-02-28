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

        request.serveFile(file);
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
