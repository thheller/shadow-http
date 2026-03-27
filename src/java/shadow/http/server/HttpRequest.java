package shadow.http.server;

import java.io.*;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.net.URLDecoder;
import java.util.*;
import java.util.zip.GZIPOutputStream;

public class HttpRequest {
    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final ZoneId GMT = ZoneId.of("GMT");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;

    public enum State {
        PENDING,
        BODY,
        COMPLETE
    }

    public enum BodyMode {
        NONE,
        FIXED_LENGTH,
        CHUNKED
    }

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] COLON_SP = {':', ' '};
    private static final byte[] HTTP11_START = {'H', 'T', 'T', 'P', '/', '1', '.', '1', ' '};

    public static String computeWebSocketAcceptKey(String wsKey) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((wsKey + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    }

    final HttpExchange exchange;

    public final String requestMethod;
    public final String requestTarget;
    public final String requestPath;
    public final String requestQueryString;
    public final String requestVersion;
    public final List<Header> requestHeadersInOrder = new ArrayList<>();
    public final Map<String, String> requestHeaders = new HashMap<>();

    BodyMode requestBodyMode = BodyMode.NONE;

    private Map<String, String> queryParams;

    InputStream requestBody;
    long requestBodyLength;

    public State state = State.PENDING;

    int responseStatus = 200;
    String responseStatusText = null;
    public final Map<String, List<String>> responseHeaders = new HashMap<>();
    private OutputStream responseOut;
    boolean responseBody = true;
    public long responseBytesWritten = 0;

    public boolean closeAfter = false;
    public boolean autoCompress = true;

    public HttpRequest(HttpExchange exchange, String requestMethod, String requestTarget, String requestVersion) {
        this.exchange = exchange;
        this.requestMethod = requestMethod;
        this.requestTarget = requestTarget;
        this.requestVersion = requestVersion;

        int qIdx = requestTarget.indexOf('?');
        if (qIdx >= 0) {
            this.requestPath = requestTarget.substring(0, qIdx);
            this.requestQueryString = requestTarget.substring(qIdx + 1);
        } else {
            this.requestPath = requestTarget;
            this.requestQueryString = null;
        }
    }

    public boolean isSecure() {
        return exchange.connection.isSecure();
    }

    public SocketAddress getRemoteAddress() {
        return exchange.connection.getRemoteAddress();
    }

    public String getRequestHeaderValue(String name) {
        return requestHeaders.get(name);
    }

    public boolean hasRequestHeader(String name) {
        return requestHeaders.containsKey(name);
    }

    // the assumption is that the client is a browser, and they usually do not send duplicate headers
    // but if something actually needs to check it can
    public boolean hasRepeatedRequestHeaders() {
        return requestHeaders.size() != requestHeadersInOrder.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(requestMethod).append(' ').append(requestTarget).append(' ').append(requestVersion).append("\r\n");
        for (Header h : requestHeadersInOrder) {
            sb.append(h.nameIn).append(": ").append(h.value).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public String getRequestMethod() {
        return requestMethod;
    }

    public String getRequestTarget() {
        return requestTarget;
    }

    public String getRequestPath() {
        return requestPath;
    }

    public String getRequestQueryString() {
        return requestQueryString;
    }

    public String getRequestVersion() {
        return requestVersion;
    }

    public Map<String, String> getQueryParams() {
        if (queryParams == null) {
            queryParams = parseQueryString(requestQueryString);
        }
        return queryParams;
    }

    public String getQueryParam(String name) {
        return getQueryParams().get(name);
    }

    public boolean hasQueryParam(String name) {
        return getQueryParams().containsKey(name);
    }

    static Map<String, String> parseQueryString(String qs) {
        if (qs == null || qs.isEmpty()) {
            return Map.of();
        }

        Map<String, String> params = new LinkedHashMap<>();
        int start = 0;
        int len = qs.length();

        while (start <= len) {
            int ampIdx = qs.indexOf('&', start);
            if (ampIdx < 0) ampIdx = len;

            String pair = qs.substring(start, ampIdx);
            start = ampIdx + 1;

            if (pair.isEmpty()) continue;

            int eqIdx = pair.indexOf('=');
            if (eqIdx >= 0) {
                params.put(decodeComponent(pair.substring(0, eqIdx)), decodeComponent(pair.substring(eqIdx + 1)));
            } else {
                params.put(decodeComponent(pair), null);
            }
        }

        return params;
    }

    private static String decodeComponent(String s) {
        // fast path: no percent-encoding or plus signs
        if (s.indexOf('%') < 0 && s.indexOf('+') < 0) {
            return s;
        }
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    public List<Header> getRequestHeadersInOrder() {
        return requestHeadersInOrder;
    }

    public Map<String, String> getRequestHeaders() {
        return requestHeaders;
    }

    public void upgradeToWebSocket(WebSocketHandler handler) throws IOException {
        upgradeToWebSocket(handler, null);
    }

    public void upgradeToWebSocket(WebSocketHandler handler, String subProtocol) throws IOException {
        // Validate required headers per RFC 6455 Section 4.2.1
        String upgrade = getRequestHeaderValue("upgrade");
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            throw new IllegalStateException("Missing or invalid Upgrade header");
        }

        String connection = getRequestHeaderValue("connection");
        if (connection == null || !connection.toLowerCase().contains("upgrade")) {
            throw new IllegalStateException("Missing or invalid Connection header");
        }

        String wsKey = getRequestHeaderValue("sec-websocket-key");
        if (wsKey == null || wsKey.isEmpty()) {
            throw new IllegalStateException("Missing Sec-WebSocket-Key header");
        }

        String wsVersion = getRequestHeaderValue("sec-websocket-version");
        if (!"13".equals(wsVersion)) {
            throw new IllegalStateException("Unsupported WebSocket version: " + wsVersion);
        }

        // Compute Sec-WebSocket-Accept per RFC 6455 Section 4.2.2
        // Concatenate key with magic GUID, SHA-1 hash, then base64 encode
        try {
            String acceptKey = computeWebSocketAcceptKey(wsKey);

            // Attempt to negotiate permessage-deflate per RFC 7692 Section 5
            String extensionsHeader = getRequestHeaderValue("sec-websocket-extensions");
            PerMessageDeflate pmd = PerMessageDeflate.negotiate(extensionsHeader);

            responseStatus = 101;
            setResponseHeader("connection", "Upgrade");
            setResponseHeader("upgrade", "websocket");
            setResponseHeader("sec-websocket-accept", acceptKey);

            if (pmd != null) {
                setResponseHeader("sec-websocket-extensions", pmd.buildResponseHeaderValue());
            }

            // Set Sec-WebSocket-Protocol if a subprotocol was selected per RFC 6455 Section 4.2.2 step 5
            if (subProtocol != null && !subProtocol.isEmpty()) {
                setResponseHeader("sec-websocket-protocol", subProtocol);
            }

            skipBody();

            // must make sure that websocket continues reading from exchange.in and doesn't try connection.getInputStream()
            // since in may still have bytes available
            exchange.connection.upgrade(new WebSocketExchange(exchange.connection, exchange.in, handler, pmd));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        exchange.upgraded = true;
    }

    /**
     * Returns true if the current request is expected to carry a message body,
     * per RFC 9112 Section 6 / 6.3:
     * <p>
     * "The presence of a message body in a request is signaled by a
     * Content-Length or Transfer-Encoding header field."
     * <p>
     * A Transfer-Encoding header always signals a body (the body length is
     * determined by the encoding itself, e.g. chunked).
     * <p>
     * A Content-Length header signals a body only when its value is greater
     * than zero; Content-Length: 0 explicitly means no content.
     */
    public boolean requestHasBody() {
        return requestBodyMode != BodyMode.NONE;
    }

    public InputStream requestBody() throws IOException {
        if (requestBody != null) {
            return requestBody;
        }

        switch (requestBodyMode) {
            case NONE -> {
                throw new IllegalStateException("Request has no body (no Content-Length or Transfer-Encoding: chunked header)");
            }
            case FIXED_LENGTH -> {
                requestBody = new ContentLengthInputStream(exchange.in, requestBodyLength);
            }
            case CHUNKED -> {
                requestBody = new ChunkedInputStream(exchange);
            }
        }

        return requestBody;
    }

    public boolean isCommitted() {
        return state != State.PENDING;
    }

    public HttpRequest setResponseStatus(int responseStatus) {
        this.responseStatus = responseStatus;
        return this;
    }

    public HttpRequest setResponseStatusText(String responseStatusText) {
        this.responseStatusText = responseStatusText;
        return this;
    }

    public HttpRequest setCloseAfter(boolean closeAfter) {
        this.closeAfter = closeAfter;
        return this;
    }

    public HttpRequest setResponseHeader(String name, String value) {
        responseHeaders.put(name, Arrays.asList(value));
        return this;
    }

    public HttpRequest addResponseHeader(String name, String value) {
        responseHeaders.computeIfAbsent(name, s -> new ArrayList<>()).add(value);
        return this;
    }


    public void skipBody() throws IOException {
        if (state == State.PENDING) {
            responseBody = false;
            beginResponse();
        } else {
            throw new IllegalStateException("can only skip body in pending state");
        }
    }

    public void respondNoContent() throws IOException {
        responseStatus = 304;
        skipBody();
    }

    public void writeString(String s) throws IOException {
        writeString(s, true);
    }

    public void writeString(String s, boolean isFinal) throws IOException {
        OutputStream out = responseBody();

        // FIXME: should respect user provided encoding
        out.write(s.getBytes(StandardCharsets.UTF_8));

        if (isFinal) {
            out.close();
        } else {
            out.flush();
        }
    }

    public void writeStream(InputStream in) throws IOException {
        writeStream(in, true);
    }

    public void writeStream(InputStream in, boolean isFinal) throws IOException {
        OutputStream out = responseBody();

        in.transferTo(out);

        if (isFinal) {
            out.close();
        } else {
            out.flush();
        }
    }

    public OutputStream responseBody() throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            beginResponse();
        }

        return responseOut;
    }

    /**
     * utility method to serve files from disk. assumes that caller verified that the file should be served.
     * does not check for any path traversal attacks. caller must verify before.
     *
     * @param file must be regular readable file
     * @throws IOException
     */
    public void serveFile(Path file) throws IOException {
        final Server server = exchange.connection.getServer();
        final FileTime lastModifiedTime = Files.getLastModifiedTime(file);
        final String lastModified = DATE_FORMATTER.format(lastModifiedTime.toInstant().atZone(GMT));

        final String ifModifiedSince = getRequestHeaderValue("if-modified-since");
        if (lastModified.equals(ifModifiedSince)) {
            respondNoContent();
        } else {
            long size = Files.size(file);

            // FIXME: maybe support range requests?

            // FIXME: don't do this per request. cache in FileInfo
            String mimeType = server.config.guessMimeType(file.getFileName().toString());

            // FIXME: config option
            boolean compress = size >= 850 && server.config.isCompressible(mimeType);

            setResponseStatus(200);
            setResponseHeader("content-type", mimeType);

            autoCompress = compress;

            // FIXME: configurable caching options
            // this is soft-cache, allows using cache but forces client to check
            // replying with 304 as above, so we don't send body again
            // this isn't ideal, but this is not a production server and during
            // dev files may change often and we never want stale files (e.g. shadow-cljs JS outputs)
            setResponseHeader("cache-control", "private, no-cache");
            setResponseHeader("last-modified", lastModified);

            // HEAD requests get headers but not body
            if ("GET".equals(requestMethod)) {
                // using the outputBufferSize since we want to fill that asap, might as well do it all at once
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), server.config.outputBufferSize)) {
                    writeStream(in);
                }
            } else {
                skipBody();
            }
        }
    }

    void beginResponse() throws IOException {
        if (state != State.PENDING) {
            throw new IllegalStateException("response already committed");
        }

        if (!responseBody) {
            // No body (HEAD, skipBody, etc.) — write headers immediately and complete
            OutputStream raw = exchange.out;
            writeStatusLine(raw, responseStatus, responseStatusText);
            writeResponseHeaders(raw);
            writeConnectionHeaders(raw);
            writeHeaderEnd(raw);
            raw.flush();
            responseOut = null;
            responseBytesWritten = 0;
            state = State.COMPLETE;
            return;
        }

        state = State.BODY;

        responseOut = new HttpOutput(this, 8192);
    }

    /**
     * Called by BufferedResponseOutputStream when the response fits in the buffer.
     * Writes headers with Content-Length and sends the body directly.
     */
    void commitFixedLength(byte[] data, int length) throws IOException {
        OutputStream raw = exchange.out;
        writeStatusLine(raw, responseStatus, responseStatusText);

        // Compress if enabled, data is large enough, and client accepts gzip.
        // Since we have all the data, we can compress into a buffer and still use Content-Length.
        if (autoCompress && length >= 850) {
            String acceptEncoding = getRequestHeaderValue("accept-encoding");
            if (acceptEncoding != null && acceptEncoding.contains("gzip")) {
                ByteArrayOutputStream compressed = new ByteArrayOutputStream(length);
                try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) {
                    gz.write(data, 0, length);
                }
                setResponseHeader("content-encoding", "gzip");
                data = compressed.toByteArray();
                length = data.length;
            }
        }

        writeResponseHeaders(raw);
        writeHeader(raw, "content-length", Integer.toString(length));
        writeConnectionHeaders(raw);
        writeHeaderEnd(raw);
        raw.write(data, 0, length);
        raw.flush();
        responseBytesWritten = length;
        state = State.COMPLETE;
    }

    /**
     * Called by BufferedResponseOutputStream when the buffer fills or is flushed.
     * Writes headers with Transfer-Encoding: chunked and returns the output stream chain.
     */
    OutputStream commitChunked() throws IOException {
        OutputStream raw = exchange.out;
        writeStatusLine(raw, responseStatus, responseStatusText);

        if (autoCompress) {
            String acceptEncoding = getRequestHeaderValue("accept-encoding");
            if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
                autoCompress = false;
            } else {
                setResponseHeader("content-encoding", "gzip");
            }
        }

        writeResponseHeaders(raw);
        writeHeader(raw, "transfer-encoding", "chunked");
        writeConnectionHeaders(raw);
        writeHeaderEnd(raw);

        OutputStream out = new InterceptedCloseOutputStream(this, raw);
        out = new ChunkedOutputStream(out);

        if (autoCompress) {
            out = new GZIPOutputStream(out, 8192, true);
        }

        return out;
    }

    private void writeResponseHeaders(OutputStream out) throws IOException {
        for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
            String name = header.getKey();
            for (String value : header.getValue()) {
                writeHeader(out, name, value);
            }
        }
    }

    private void writeConnectionHeaders(OutputStream out) throws IOException {
        if (!closeAfter && "close".equals(getRequestHeaderValue("connection"))) {
            closeAfter = true;
        }
        if (closeAfter) {
            writeHeader(out, "connection", "close");
        } else if ("HTTP/1.0".equals(requestVersion)) {
            writeHeader(out, "connection", "keep-alive");
        }
    }

    /**
     * validates and extracts some data from request headers after parsing is finished
     */
    void prepare() throws BadRequestException {
        /*
          Section 3.2: A client MUST send a Host header field in all HTTP/1.1
          request messages. A server MUST respond with 400 if missing or duplicated.
         */
        if ("HTTP/1.1".equals(requestVersion)) {
            int hostCount = 0;

            if (hasRepeatedRequestHeaders()) {
                for (Header h : requestHeadersInOrder) {
                    if (h.name.equals("host")) {
                        hostCount++;
                    }
                }
                if (hostCount == 0) {
                    throw new BadRequestException("Missing required Host header field in HTTP/1.1 request");
                }
                if (hostCount > 1) {
                    throw new BadRequestException("Multiple Host header fields in HTTP/1.1 request");
                }
            } else if (!hasRequestHeader("host")) {
                throw new BadRequestException("Missing required Host header field in HTTP/1.1 request");
            }
        } else if ("HTTP/1.0".equals(requestVersion)) {
            closeAfter = !getRequestHeaderValue("connection").equalsIgnoreCase("keep-alive");
        } else {
            throw new BadRequestException("Unsupported HTTP Version: " + requestVersion);
        }


        String transferEncoding = getRequestHeaderValue("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            requestBodyMode = BodyMode.CHUNKED;
        }

        // chunked wins. FIXME: spec says sending both is violation
        String contentLengthHeader = getRequestHeaderValue("content-length");
        if (requestBodyMode != BodyMode.CHUNKED && contentLengthHeader != null) {
            long contentLength;
            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("Invalid Content-Length header: " + contentLengthHeader);
            }
            if (contentLength < 0) {
                throw new BadRequestException("Negative Content-Length: " + contentLength);
            }

            if (contentLength > exchange.connection.getServer().getConfig().maximumRequestBodySize) {
                throw new BadRequestException("Request Content-Length exceeds maximum acceptable size: " + contentLength) ;
            }

            requestBodyLength = contentLength;
            requestBodyMode = BodyMode.FIXED_LENGTH;
        }
    }


    void checkComplete() {
        if (state == State.COMPLETE) {
            throw new IllegalStateException("HttpRequest already completed");
        }
    }

    void writeStatusLine(OutputStream out, int statusCode, String reasonPhrase) throws IOException {
        // FIXME: what about http/1.0? what happens if we answer 1.1 to a 1.0 request? does anything actually use 1.0?
        out.write(HTTP11_START);

        // status-code = 3DIGIT
        out.write('0' + (statusCode / 100));
        out.write('0' + (statusCode / 10) % 10);
        out.write('0' + statusCode % 10);
        out.write(' ');

        if (reasonPhrase != null && !reasonPhrase.isEmpty()) {
            out.write(reasonPhrase.getBytes(StandardCharsets.US_ASCII));
        }

        out.write(CRLF);
    }

    void writeHeader(OutputStream out, String name, String value) throws IOException {
        out.write(name.getBytes(StandardCharsets.US_ASCII));
        out.write(COLON_SP);
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    void writeHeaderEnd(OutputStream out) throws IOException {
        out.write(CRLF);
    }
}
