package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class IndexingFileHandler implements HttpHandler, AutoCloseable {

    public static final ZoneId GMT = ZoneId.of("GMT");
    public static final DateTimeFormatter LAST_MODIFIED_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    final Path root;

    ConcurrentHashMap<String, FileInfo> files = new ConcurrentHashMap<>();

    Watcher watcher = null;
    Thread watcherThread = null;

    public IndexingFileHandler(Path root) {
        this.root = root;
    }

    public static boolean servableFile(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.isReadable(path) && !Files.isHidden(path);
    }

    public IndexingFileHandler findFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.forEach((path) -> {
                try {
                    if (Files.isReadable(path) && !Files.isHidden(path)) {
                        addFile(keyForPath(path), path);
                    }
                } catch (IOException e) {
                    // FIXME: silently ignore or log somewhere?
                }
            });
        }

        return this;
    }

    public IndexingFileHandler watch() throws IOException {
        if (watcher != null) {
            throw new IllegalStateException("already watching");
        }

        watcher = new Watcher(this, FileSystems.getDefault().newWatchService());
        watcher.watchAll();

        watcherThread = new Thread(watcher, "shadow.http.IndexingFileHandler:watch[" + root.toString() + "]");
        watcherThread.setDaemon(true);
        watcherThread.start();

        return this;
    }

    public void watcherStop() throws IOException, InterruptedException {
        if (watcher != null) {
            watcher.watchService.close();
            watcherThread.join();
        }
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

        FileInfo fileInfo = files.get(uri);

        if (fileInfo == null || fileInfo.isDirectory()) {
            if (uri.endsWith("/")) {
                fileInfo = files.get(uri + "index.html");
            } else { // FIXME: make this configuration, don't always want /foo to serve /foo/index.html
                fileInfo = files.get(uri + "/index.html");
            }
        }

        if (fileInfo == null) {
            return;
        }

        // sanity check, might have been deleted and watcher hasn't updated yet
        if (!servableFile(fileInfo.path)) {
            return;
        }

        request.serveFile(fileInfo.path);
    }

    public void close() throws Exception {
        watcherStop();
    }

    String keyForPath(Path path) {
        Path rel = root.relativize(path);
        return "/" + rel.toString().replace("\\", "/");
    }

    void addFile(String key, Path path) throws IOException {
        files.put(key, gatherFileInfo(key, path));
    }

    FileInfo gatherFileInfo(String key, Path path) throws IOException {
        return new FileInfo(key, path);
    }

    public static IndexingFileHandler forPath(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("root dir does not exist");
        }

        return new IndexingFileHandler(root.normalize().toAbsolutePath());
    }

    public static IndexingFileHandler forPath(File file) throws IOException {
        return forPath(file.getAbsoluteFile().toPath());
    }

    public static IndexingFileHandler forPath(String base) throws IOException {
        return forPath(Paths.get(base));
    }

    public static IndexingFileHandler forPath(String base, String... more) throws IOException {
        return forPath(Paths.get(base, more));
    }

    public static class FileInfo {
        final String key;
        final Path path;
        final boolean directory;

        public FileInfo(String key, Path path) {
            this.key = key;
            this.path = path;

            // caching this here since we need it often
            // and the file watcher can't check if it was a directory after it's been deleted
            this.directory = Files.isDirectory(path);
        }

        public boolean isDirectory() {
            return directory;
        }

        // called by watcher if the file was modified, invalidate if needed
        void fileModified() {
            System.out.println("File was modified: " + path.toString());
        }

        void fileDeleted() {
            System.out.println("File was deleted: " + path.toString());
        }

    }

    public static class Watcher implements Runnable {
        final IndexingFileHandler handler;
        final WatchService watchService;
        private final Map<WatchKey, Path> watchKeys = new HashMap<>();

        public Watcher(IndexingFileHandler handler, WatchService watchService) {
            this.handler = handler;
            this.watchService = watchService;
        }

        private void watchDir(Path dir) {
            try {
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchKeys.put(key, dir);
            } catch (IOException e) {
                // FIXME: don't just ignore
            }
        }

        public void watchAll() throws IOException {
            try (Stream<Path> stream = Files.walk(handler.root)) {
                stream.filter(Files::isDirectory).forEach(dir -> watchDir(dir));
            }
        }

        public void run() {
            try {

                while (true) {
                    WatchKey watchKey = watchService.take();
                    Path watchDir = watchKeys.get(watchKey);

                    for (WatchEvent<?> event : watchKey.pollEvents()) {
                        Path file = watchDir.resolve((Path) event.context());

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            if (Files.isDirectory(file)) {
                                watchDir(file);
                            }

                            try {
                                handler.addFile(handler.keyForPath(file), file);
                            } catch (IOException e) {
                                // FIXME: don't just ignore
                            }
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                            String key = handler.keyForPath(file);
                            FileInfo fileInfo = handler.files.remove(key);

                            if (fileInfo != null) {
                                fileInfo.fileDeleted();

                                if (fileInfo.isDirectory()) {
                                    // macOS only gets DELETE for directory, not its contents
                                    // FIXME: check if true for other OS

                                    String folder = key + "/";

                                    handler.files.entrySet().removeIf(entry -> {
                                        if (entry.getKey().startsWith(folder)) {
                                            entry.getValue().fileDeleted();
                                            return true;
                                        }
                                        return false;
                                    });
                                }
                            }
                        } else {
                            String path = handler.keyForPath(file);
                            FileInfo fileInfo = handler.files.get(path);
                            if (fileInfo != null) {
                                fileInfo.fileModified();
                            }
                        }
                    }
                    watchKey.reset();
                }
            } catch (InterruptedException e) {
            }
        }
    }

    public static void main(String[] args) throws Exception {
        IndexingFileHandler b = forPath(Paths.get("docs")).findFiles().watch();

        b.watcherThread.join();
    }
}
