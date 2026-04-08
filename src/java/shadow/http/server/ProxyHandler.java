package shadow.http.server;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

public class ProxyHandler implements HttpHandler {

    // Hop-by-hop headers that must not be forwarded (lowercase)
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host"
    );

    // Headers to skip when forwarding a WebSocket upgrade request.
    // Connection, Upgrade, and Sec-WebSocket-* headers must be forwarded.
    private static final Set<String> WS_SKIP_HEADERS = Set.of(
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization"
    );

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] COLON_SP = {':', ' '};

    private final URI target;
    private final SSLSocketFactory sslSocketFactory;
    private final int connectTimeout;

    public ProxyHandler(URI target) {
        this(target, null, 5000);
    }

    public ProxyHandler(URI target, SSLContext sslContext) {
        this(target, sslContext, 5000);
    }

    public ProxyHandler(URI target, SSLContext sslContext, int connectTimeout) {
        this.target = target;
        this.sslSocketFactory = sslContext != null ? sslContext.getSocketFactory() : null;
        this.connectTimeout = connectTimeout;
    }

    private Socket openSocket() throws IOException {
        String scheme = target.getScheme();
        boolean ssl = "https".equalsIgnoreCase(scheme);

        String host = target.getHost();
        int port = target.getPort();
        if (port == -1) {
            port = ssl ? 443 : 80;
        }

        Socket socket;
        if (ssl) {
            SSLSocketFactory factory = sslSocketFactory;
            if (factory == null) {
                try {
                    factory = SSLContext.getDefault().getSocketFactory();
                } catch (Exception e) {
                    throw new IOException("Failed to obtain default SSLContext", e);
                }
            }
            socket = factory.createSocket();
        } else {
            socket = new Socket();
        }

        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), connectTimeout);
        return socket;
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        if (isWebSocketUpgrade(request)) {
            handleWebSocketProxy(request);
            return;
        }

        // Build target URI by appending the request target to the configured base
        var uri = target.resolve(request.requestTarget);

        try (Socket socket = openSocket()) {
            OutputStream sockOut = new BufferedOutputStream(socket.getOutputStream(), 8192);
            HttpInput sockIn = new HttpInput(socket.getInputStream(), 8192);

            // Write request line: METHOD /path HTTP/1.1\r\n
            sockOut.write(request.requestMethod.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(' ');

            // Use the full URI path (+ query) as the request-target
            String requestTarget = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                requestTarget = requestTarget + "?" + uri.getRawQuery();
            }
            if (requestTarget == null || requestTarget.isEmpty()) {
                requestTarget = "/";
            }
            sockOut.write(requestTarget.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(' ');
            sockOut.write("HTTP/1.1".getBytes(StandardCharsets.US_ASCII));
            sockOut.write(CRLF);

            // Write Host header
            String host = uri.getHost();
            int port = uri.getPort();
            String hostHeader = (port != -1 && port != 80 && port != 443)
                    ? host + ":" + port
                    : host;
            sockOut.write("Host".getBytes(StandardCharsets.US_ASCII));
            sockOut.write(COLON_SP);
            sockOut.write(hostHeader.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(CRLF);

            // Write Connection: close
            sockOut.write("Connection: close".getBytes(StandardCharsets.US_ASCII));
            sockOut.write(CRLF);

            // Forward request headers, skipping hop-by-hop
            for (Header h : request.requestHeadersInOrder) {
                if (!HOP_BY_HOP.contains(h.name)) {
                    sockOut.write(h.nameIn.getBytes(StandardCharsets.ISO_8859_1));
                    sockOut.write(COLON_SP);
                    sockOut.write(h.value.getBytes(StandardCharsets.ISO_8859_1));
                    sockOut.write(CRLF);
                }
            }

            // End headers
            sockOut.write(CRLF);

            // Forward request body if present
            if (request.requestHasBody()) {
                try (InputStream reqBody = request.requestBody()) {
                    reqBody.transferTo(sockOut);
                }
            }

            sockOut.flush();

            // Parse response status line
            sockIn.readResponseVersion();
            int statusCode = sockIn.readStatusCode();
            sockIn.readReasonPhrase();

            request.setResponseStatus(statusCode);

            // Parse and forward response headers
            long contentLength = -1;
            boolean chunked = false;

            while (true) {
                Header header = sockIn.readHeader();
                if (header == null) {
                    break;
                }

                if (HOP_BY_HOP.contains(header.name)) {
                    // Check for chunked transfer-encoding before skipping
                    if ("transfer-encoding".equals(header.name)
                            && header.value.toLowerCase().contains("chunked")) {
                        chunked = true;
                    }
                    continue;
                }

                if ("content-length".equals(header.name)) {
                    try {
                        contentLength = Long.parseLong(header.value.trim());
                    } catch (NumberFormatException e) {
                        // skip malformed content-length
                        continue;
                    }
                    request.setResponseHeader(header.name, header.value);
                    continue;
                }

                if ("set-cookie".equals(header.name) && !request.isSecure()) {
                    request.setResponseHeader(header.name, stripSecureFlag(header.value));
                } else {
                    request.setResponseHeader(header.name, header.value);
                }
            }

            // Let the server handle chunking/compression itself
            request.autoCompress = false;
            request.autoChunk = true;

            if (contentLength >= 0) {
                request.responseLength = contentLength;
                request.autoChunk = false;
            }

            // Stream the response body
            if (contentLength == 0 || isBodylessStatus(statusCode)) {
                request.skipBody();
            } else if (contentLength > 0) {
                try (InputStream body = new ContentLengthInputStream(sockIn, contentLength)) {
                    request.writeStream(body);
                }
            } else if (chunked) {
                // Read chunks manually from sockIn, stream decoded bytes
                request.writeStream(new ChunkedBodyInputStream(sockIn));
            } else {
                // No content-length, not chunked, connection: close — read until EOF
                request.writeStream(sockIn);
            }
        } catch (Exception e) {
            if (request.isCommitted()) {
                // Can't send error response after headers are already committed
                throw new IOException("Proxy error after response started", e);
            }
            request.setResponseStatus(502);
            request.setResponseHeader("content-type", "text/plain; charset=utf-8");
            try (HttpOutput out = new HttpOutput(request, 2048)) {
                out.write("FAILED TO PROXY REQUEST TO: ");
                out.write(uri.toString());
                out.write("\n-----\n");
                out.write(request.toString());
                out.write("\n-----\n");
                try (PrintWriter pw = new PrintWriter(out)) {
                    e.printStackTrace(pw);
                }
            }
        }
    }

    private static boolean isWebSocketUpgrade(HttpRequest request) {
        String upgrade = request.getRequestHeaderValue("upgrade");
        String connection = request.getRequestHeaderValue("connection");
        return upgrade != null && upgrade.equalsIgnoreCase("websocket")
                && connection != null && connection.toLowerCase().contains("upgrade");
    }

    private void handleWebSocketProxy(HttpRequest request) throws IOException {
        var uri = target.resolve(request.requestTarget);
        Socket upstreamSocket = openSocket();

        try {
            OutputStream sockOut = new BufferedOutputStream(upstreamSocket.getOutputStream(), 8192);
            HttpInput sockIn = new HttpInput(upstreamSocket.getInputStream(), 8192);

            // Write request line
            sockOut.write(request.requestMethod.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(' ');

            String requestTarget = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                requestTarget = requestTarget + "?" + uri.getRawQuery();
            }
            if (requestTarget == null || requestTarget.isEmpty()) {
                requestTarget = "/";
            }
            sockOut.write(requestTarget.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(' ');
            sockOut.write("HTTP/1.1".getBytes(StandardCharsets.US_ASCII));
            sockOut.write(CRLF);

            // Write Host header for the upstream target
            String host = uri.getHost();
            int port = uri.getPort();
            String hostHeader = (port != -1 && port != 80 && port != 443)
                    ? host + ":" + port
                    : host;
            sockOut.write("Host".getBytes(StandardCharsets.US_ASCII));
            sockOut.write(COLON_SP);
            sockOut.write(hostHeader.getBytes(StandardCharsets.US_ASCII));
            sockOut.write(CRLF);

            // Forward all request headers (including Connection, Upgrade, Sec-WebSocket-*)
            // except host (already written) and proxy-specific headers
            for (Header h : request.requestHeadersInOrder) {
                if (!WS_SKIP_HEADERS.contains(h.name)) {
                    sockOut.write(h.nameIn.getBytes(StandardCharsets.ISO_8859_1));
                    sockOut.write(COLON_SP);
                    sockOut.write(h.value.getBytes(StandardCharsets.ISO_8859_1));
                    sockOut.write(CRLF);
                }
            }

            sockOut.write(CRLF);
            sockOut.flush();

            // Read upstream response
            sockIn.readResponseVersion();
            int statusCode = sockIn.readStatusCode();
            sockIn.readReasonPhrase();

            if (statusCode != 101) {
                // Upstream refused the WebSocket upgrade
                upstreamSocket.close();
                request.setResponseStatus(502);
                request.setResponseHeader("content-type", "text/plain; charset=utf-8");
                try (HttpOutput out = new HttpOutput(request, 2048)) {
                    out.write("WebSocket upgrade refused by upstream (status ");
                    out.write(Integer.toString(statusCode));
                    out.write("): ");
                    out.write(uri.toString());
                }
                return;
            }

            // Forward 101 response to client
            request.setResponseStatus(101);

            while (true) {
                Header header = sockIn.readHeader();
                if (header == null) {
                    break;
                }
                request.setResponseHeader(header.name, header.value);
            }

            request.skipBody();

            // Switch to raw bidirectional byte forwarding.
            // Use exchange.in (not connection.getInputStream()) because it may have buffered data.
            // Use sockIn for the same reason on the upstream side.
            request.exchange.connection.upgrade(
                    new ProxyWebSocketExchange(request.exchange.connection, request.exchange.in, upstreamSocket, sockIn)
            );
            request.exchange.upgraded = true;

        } catch (Exception e) {
            if (!upstreamSocket.isClosed()) {
                upstreamSocket.close();
            }

            if (request.isCommitted()) {
                throw new IOException("Proxy WebSocket error after response started", e);
            }
            request.setResponseStatus(502);
            request.setResponseHeader("content-type", "text/plain; charset=utf-8");
            try (HttpOutput out = new HttpOutput(request, 2048)) {
                out.write("FAILED TO PROXY WEBSOCKET REQUEST TO: ");
                out.write(uri.toString());
                out.write("\n-----\n");
                out.write(request.toString());
                out.write("\n-----\n");
                try (PrintWriter pw = new PrintWriter(out)) {
                    e.printStackTrace(pw);
                }
            }
        }
    }

    private static boolean isBodylessStatus(int status) {
        return status == 204 || status == 304 || (status >= 100 && status < 200);
    }

    private static final java.util.regex.Pattern SECURE_FLAG =
            java.util.regex.Pattern.compile("(;\\s*)(?i:Secure)(\\s*(?:;|$))");

    private static String stripSecureFlag(String value) {
        return SECURE_FLAG.matcher(value).replaceAll("$2");
    }

    /**
     * InputStream that reads chunked transfer-encoding from an HttpInput,
     * presenting decoded chunk data as a contiguous stream.
     */
    private static class ChunkedBodyInputStream extends InputStream {
        private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024;

        private final HttpInput in;
        private byte[] currentChunk;
        private int currentOffset;
        private boolean eof;

        ChunkedBodyInputStream(HttpInput in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            if (eof) return -1;
            if (!ensureChunkData()) return -1;
            return currentChunk[currentOffset++] & 0xFF;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (eof) return -1;
            if (len == 0) return 0;
            if (!ensureChunkData()) return -1;
            int available = currentChunk.length - currentOffset;
            int toCopy = Math.min(len, available);
            System.arraycopy(currentChunk, currentOffset, buf, off, toCopy);
            currentOffset += toCopy;
            return toCopy;
        }

        @Override
        public int available() {
            if (eof || currentChunk == null) return 0;
            return currentChunk.length - currentOffset;
        }

        @Override
        public void close() throws IOException {
            while (!eof) {
                if (!ensureChunkData()) break;
                currentChunk = null;
                currentOffset = 0;
            }
        }

        private boolean ensureChunkData() throws IOException {
            while (currentChunk == null || currentOffset >= currentChunk.length) {
                Chunk chunk = in.readChunk(MAX_CHUNK_SIZE);
                if (chunk.isLast()) {
                    eof = true;
                    currentChunk = null;
                    currentOffset = 0;
                    return false;
                }
                currentChunk = chunk.data();
                currentOffset = 0;
            }
            return true;
        }
    }
}
