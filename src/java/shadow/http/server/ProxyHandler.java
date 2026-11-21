package shadow.http.server;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
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

        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), connectTimeout);
            if (!ssl) return socket;

            SSLSocketFactory factory = sslSocketFactory != null
                    ? sslSocketFactory : (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket(socket, host, port, true);
            var parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            return tls;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    // Build upstream request-target by prepending the configured base path directly,
    // without URI.resolve (which rejects valid HTTP query characters like []/{} and
    // treats leading // as a network-path authority).
    private String buildUpstreamTarget(String requestTarget) {
        String basePath = target.getRawPath();

        if (basePath == null || basePath.isEmpty() || basePath.equals("/")) {
            return requestTarget.isEmpty() ? "/" : requestTarget;
        }

        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }

        return requestTarget.startsWith("/") ? basePath + requestTarget : basePath + "/" + requestTarget;
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        Set<String> options = connectionOptions(request.requestHeadersInOrder);
        boolean upgrade = "websocket".equalsIgnoreCase(request.getRequestHeaderValue("upgrade"))
                && options.contains("upgrade");
        Socket upstream = null;
        try {
            String upstreamTarget = buildUpstreamTarget(request.requestTarget);
            upstream = openSocket();
            OutputStream out = new UnsyncBufferedOutputStream(upstream.getOutputStream(), 8192);
            HttpInput in = new HttpInput(upstream.getInputStream(), 8192);
            writeRequestHeaders(request, upstreamTarget, out, options, upgrade);

            if (request.requestHasBody()) {
                try {
                    InputStream body = request.requestBody();
                    if (request.requestBodyMode == HttpRequest.BodyMode.CHUNKED) {
                        ChunkedOutputStream chunks = new ChunkedOutputStream(out);
                        body.transferTo(chunks);
                        chunks.finish();
                    } else {
                        body.transferTo(out);
                    }
                } catch (BadRequestException | EOFException e) {
                    request.setCloseAfter(true).setResponseStatus(400).writeString("Invalid request body");
                    return;
                }
            }
            out.flush();

            for (;;) {
                in.readResponseVersion();
                int status = in.readStatusCode();
                String reason = in.readReasonPhrase();
                List<Header> headers = new ArrayList<>();
                Header h;
                while ((h = in.readHeader()) != null) {
                    if (headers.size() == HttpExchange.MAX_HEADERS) throw new IOException("Too many upstream headers");
                    headers.add(h);
                }

                if (status >= 200 || status == 101) {
                    if (status == 101 && !upgrade) throw new IOException("Unexpected upgrade response");
                    forwardResponse(request, in, status, headers);
                    if (status == 101) {
                        request.exchange.connection.upgrade(
                                new ProxyWebSocketExchange(request.exchange.connection, request.exchange.in, upstream, in));
                        request.exchange.upgraded = true;
                        upstream = null; // The tunnel now owns the socket.
                    }
                    return;
                }

                // We handle 100-continue locally. Other informational replies precede the final response.
                if (status != 100 && "HTTP/1.1".equals(request.requestVersion)) {
                    Set<String> responseOptions = connectionOptions(headers);
                    request.writeStatusLine(status, reason);
                    for (Header header : headers) {
                        if (!skipHeader(header, responseOptions) && !HttpRequest.isContentLength(header.name)) {
                            request.writeHeader(header.nameIn, header.value);
                        }
                    }
                    request.writeHeaderEnd();
                    request.exchange.out.flush();
                }
            }
        } catch (IOException e) {
            if (request.isCommitted()) throw e;
            request.setCloseAfter(true).setResponseStatus(502)
                    .setResponseHeader("content-type", "text/plain; charset=utf-8")
                    .writeString("Upstream request to " + target.getHost() + " failed (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (upstream != null) upstream.close();
        }
    }

    private static Set<String> connectionOptions(List<Header> headers) {
        Set<String> options = new HashSet<>();
        for (Header h : headers) {
            if ("connection".equals(h.name)) {
                for (String token : h.value.split(",")) {
                    options.add(token.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return options;
    }

    private static boolean skipHeader(Header header, Set<String> options) {
        return HOP_BY_HOP.contains(header.name) || options.contains(header.name);
    }

    private void writeRequestHeaders(HttpRequest request, String path, OutputStream out, Set<String> options, boolean upgrade) throws IOException {
        String host = target.getHost();
        if (target.getPort() != -1) host += ":" + target.getPort();
        out.write((request.requestMethod + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        String connection = upgrade ? "Connection: Upgrade\r\nUpgrade: websocket\r\n" : "Connection: close\r\n";
        out.write(connection.getBytes(StandardCharsets.US_ASCII));
        for (Header h : request.requestHeadersInOrder) {
            if (skipHeader(h, options) || "expect".equals(h.name) || HttpRequest.isContentLength(h.name)) continue;
            out.write(h.nameIn.getBytes(StandardCharsets.US_ASCII));
            out.write(COLON_SP);
            out.write(h.value.getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
        }
        switch (request.requestBodyMode) {
            case FIXED_LENGTH -> out.write(("Content-Length: " + request.requestBodyLength + "\r\n").getBytes(StandardCharsets.US_ASCII));
            case CHUNKED -> out.write("Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(CRLF);
    }

    private static void forwardResponse(HttpRequest request, HttpInput in, int status, List<Header> headers) throws IOException {
        Set<String> options = connectionOptions(headers);
        long length = -1;
        boolean chunked = false;
        for (Header h : headers) {
            if (HttpRequest.isContentLength(h.name)) {
                long value = HttpRequest.parseContentLength(h.value);
                if (length >= 0 && length != value) throw new IOException("Conflicting upstream content lengths");
                length = value;
            } else if ("transfer-encoding".equals(h.name)) {
                if (chunked || !"chunked".equalsIgnoreCase(h.value)) throw new IOException("Unsupported upstream transfer coding");
                chunked = true;
            }
        }
        if (chunked && length >= 0) throw new IOException("Conflicting upstream response framing");

        request.setResponseStatus(status);
        for (Header h : headers) {
            if (skipHeader(h, options) || HttpRequest.isContentLength(h.name)) continue;
            String value = "set-cookie".equals(h.name) && !request.isSecure() ? stripSecureFlag(h.value) : h.value;
            request.addResponseHeader(h.name, value);
        }
        request.autoCompress = false;
        request.autoChunk = length < 0;
        request.responseLength = length;
        if (status == 101) {
            request.setResponseHeader("connection", "Upgrade");
            for (Header header : headers) {
                if ("upgrade".equals(header.name)) request.setResponseHeader("upgrade", header.value);
            }
        }
        if ("HEAD".equals(request.requestMethod) || HttpRequest.isBodylessStatus(status) || length == 0) {
            request.skipBody();
        } else if (chunked) {
            streamWithFlush(request, new ChunkedInputStream(in));
        } else if (length > 0) {
            request.writeStream(new ContentLengthInputStream(in, length));
        } else {
            streamWithFlush(request, in);
        }
    }

    // Flush after every read so SSE / chunked streams don't stall inside the 64 KB output buffer
    private static void streamWithFlush(HttpRequest request, InputStream source) throws IOException {
        try (OutputStream out = request.responseBody()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = source.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        }
    }

    private static final java.util.regex.Pattern SECURE_FLAG =
            java.util.regex.Pattern.compile("(;\\s*)(?i:Secure)(\\s*(?:;|$))");

    private static String stripSecureFlag(String value) {
        return SECURE_FLAG.matcher(value).replaceAll("$2");
    }
}
