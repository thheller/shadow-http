package shadow.http.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Config {
    final Map<String, String> mimeTypes = new HashMap<>();
    final Set<String> compressibleTypes = new HashSet<>();

    public static final Config DEFAULT = new Config();

    // directly affects how much memory each connection uses
    // larger input isn't all that relevant
    // larger output means better performance for larger responses
    // smaller means less memory use overall, but that is only really relevant
    // when having a lof of open connections. which we won't really have
    // in a shadow-cljs setting. this seems like a good balance
    int inputBufferSize = 8192;
    int outputBufferSize = 65536; //  32768;

    long maximumRequestBodySize = 10_000_000;
    private int maximumWebSocketMessageSize = 16 * 1024 * 1024;
    boolean useIndexFiles = true;

    public Config() {
        mimeTypes.put("html", "text/html");
        mimeTypes.put("htm", "text/html");
        mimeTypes.put("css", "text/css");

        mimeTypes.put("clj", "text/plain");
        mimeTypes.put("cljs", "text/plain");
        mimeTypes.put("cljc", "text/plain");
        mimeTypes.put("cljx", "text/plain");

        mimeTypes.put("js", "application/javascript");
        mimeTypes.put("mjs", "application/javascript");
        mimeTypes.put("json", "application/json");
        mimeTypes.put("map", "application/json");
        mimeTypes.put("xml", "application/xml");
        mimeTypes.put("txt", "text/plain");

        // Image types
        mimeTypes.put("png", "image/png");
        mimeTypes.put("jpg", "image/jpeg");
        mimeTypes.put("jpeg", "image/jpeg");
        mimeTypes.put("gif", "image/gif");
        mimeTypes.put("svg", "image/svg+xml");
        mimeTypes.put("webp", "image/webp");
        mimeTypes.put("ico", "image/x-icon");

        // Font types
        mimeTypes.put("woff", "font/woff");
        mimeTypes.put("woff2", "font/woff2");
        mimeTypes.put("ttf", "font/ttf");
        mimeTypes.put("otf", "font/otf");

        // Application types
        mimeTypes.put("pdf", "application/pdf");
        mimeTypes.put("zip", "application/zip");
        mimeTypes.put("wasm", "application/wasm");

        // Media types
        mimeTypes.put("mp4", "video/mp4");
        mimeTypes.put("webm", "video/webm");
        mimeTypes.put("mp3", "audio/mpeg");
        mimeTypes.put("wav", "audio/wav");

        // Compressible types
        compressibleTypes.add("text/html");
        compressibleTypes.add("text/css");
        compressibleTypes.add("text/plain");
        compressibleTypes.add("application/javascript");
        compressibleTypes.add("application/json");
        compressibleTypes.add("application/xml");
        compressibleTypes.add("image/svg+xml");
        compressibleTypes.add("application/wasm");
    }

    public long getMaximumRequestBodySize() {
        return maximumRequestBodySize;
    }

    public void setMaximumRequestBodySize(long maximumRequestBodySize) {
        if (maximumRequestBodySize < 0) throw new IllegalArgumentException("Negative request body limit");
        this.maximumRequestBodySize = maximumRequestBodySize;
    }

    public int getMaximumWebSocketMessageSize() {
        return maximumWebSocketMessageSize;
    }

    public void setMaximumWebSocketMessageSize(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative WebSocket message limit");
        maximumWebSocketMessageSize = bytes;
    }

    public int getInputBufferSize() {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize) {
        if (inputBufferSize <= 0) throw new IllegalArgumentException("Input buffer size must be > 0");
        this.inputBufferSize = inputBufferSize;
    }

    public int getOutputBufferSize() {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize) {
        if (outputBufferSize <= 0) throw new IllegalArgumentException("Output buffer size must be > 0");
        this.outputBufferSize = outputBufferSize;
    }

    public boolean isUseIndexFiles() {
        return useIndexFiles;
    }

    public void setUseIndexFiles(boolean useIndexFiles) {
        this.useIndexFiles = useIndexFiles;
    }

    public void addMimeType(String extension, String mimeType) {
        mimeTypes.put(extension.toLowerCase(Locale.ROOT), mimeType);
    }

    public void addCompressibleType(String mimeType) {
        compressibleTypes.add(mimeType.toLowerCase(Locale.ROOT));
    }

    public String guessMimeType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) {
            return "application/octet-stream";
        }

        String extension = filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return mimeTypes.getOrDefault(extension, "application/octet-stream");
    }

    public boolean isCompressible(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        int semi = mimeType.indexOf(';');
        String base = (semi >= 0 ? mimeType.substring(0, semi) : mimeType).trim().toLowerCase(Locale.ROOT);
        return compressibleTypes.contains(base);
    }
}
