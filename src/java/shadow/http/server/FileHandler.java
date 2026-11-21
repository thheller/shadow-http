package shadow.http.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileHandler implements HttpHandler {

    final Path root;
    final Path realRoot;

    public FileHandler(Path root) {
        this.root = root;
        Path resolved;
        try {
            resolved = root.toRealPath();
        } catch (IOException e) {
            resolved = root.normalize().toAbsolutePath();
        }
        this.realRoot = resolved;
    }

    public static boolean servableFile(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.isReadable(path) && !Files.isHidden(path);
    }

    public static boolean supportedRequestType(HttpRequest request) {
        return switch (request.requestMethod) {
            case "GET", "POST", "HEAD" -> true;
            default -> false;
        };
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        if (!supportedRequestType(request)) {
            return;
        }

        // FIXME: should this all be case insensitive? probably not right?
        // only make sense for case insensitive file systems, otherwise foo.txt and FOO.txt might exist
        // but we could only serve one?
        String uri = request.getRequestPath();

        if (!uri.startsWith("/") || uri.indexOf('\0') >= 0) {
            return;
        }

        Path file;
        try {
            file = root.resolve(uri.substring(1)).normalize();
        } catch (InvalidPathException e) {
            return;
        }

        // prevent path traversal attacks (e.g. /../../../etc/passwd)
        if (!file.startsWith(root)) {
            return;
        }

        if (Files.isDirectory(file) && request.getConfig().useIndexFiles) {
            file = file.resolve("index.html");
        }

        if (!servableFile(file)) {
            return;
        }

        serveContainedFile(request, realRoot, file);
    }

    static void serveContainedFile(HttpRequest request, Path realRoot, Path file) throws IOException {
        Path realFile = file.toRealPath();
        if (realFile.startsWith(realRoot)) {
            request.serveFile(realFile, file.getFileName().toString());
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
