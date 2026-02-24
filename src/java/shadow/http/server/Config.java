package shadow.http.server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Config {
    final Map<String, String> mimeTypes = new HashMap<>();
    final Set<String> compressibleTypes = new HashSet<>();

    public Config() {
        mimeTypes.put("html", "text/html");
        mimeTypes.put("htm", "text/html");
        mimeTypes.put("css", "text/css");
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
