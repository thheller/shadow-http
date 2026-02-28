package shadow.http.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Config {
    final Map<String, String> mimeTypes = new HashMap<>();
    final Set<String> compressibleTypes = new HashSet<>();

    // directly affects how much memory each connection uses
    // larger input isn't all that relevant
    // larger output means better performance for larger responses
    // smaller means less memory use overall, but that is only really relevant
    // when having a lof of open connections. which we won't really have
    // in a shadow-cljs setting. this seems like a good balance
    int inputBufferSize = 8192;
    int outputBufferSize = 65536; //  32768;

    long maximumRequestBodySize = 10_000_000;

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
        compressibleTypes.add("font/woff");
        compressibleTypes.add("font/woff2");
    }

    public long getMaximumRequestBodySize() {
        return maximumRequestBodySize;
    }

    public void setMaximumRequestBodySize(long maximumRequestBodySize) {
        this.maximumRequestBodySize = maximumRequestBodySize;
    }

    public int getInputBufferSize() {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize) {
        this.inputBufferSize = inputBufferSize;
    }

    public int getOutputBufferSize() {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize) {
        this.outputBufferSize = outputBufferSize;
    }

    public String guessMimeType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == filename.length() - 1) {
            return "application/octet-stream";
        }

        String extension = filename.substring(lastDot + 1).toLowerCase();
        return mimeTypes.getOrDefault(extension, "application/octet-stream");
    }

    public boolean isCompressible(String mimeType) {
        return compressibleTypes.contains(mimeType);
    }
}
