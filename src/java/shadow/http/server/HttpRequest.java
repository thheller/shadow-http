package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.regex.Pattern;
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
    OutputStream responseOut;
    boolean responseBody = true;
    public long responseBytesWritten = 0;

    public boolean closeAfter = false;
    public boolean autoCompress = true;
    public boolean autoChunk = true;
    public long responseLength = -1;

    public HttpRequest(HttpExchange exchange, String requestMethod, String requestTarget, String requestVersion) {
        this.exchange = exchange;
        this.requestMethod = requestMethod;
        this.requestTarget = requestTarget;
        this.requestVersion = requestVersion;

        int qIdx = requestTarget.indexOf('?');
        if (qIdx >= 0) {
            this.requestPath = decodeComponent(requestTarget.substring(0, qIdx));
            this.requestQueryString = requestTarget.substring(qIdx + 1);
        } else {
            this.requestPath = decodeComponent(requestTarget);
            this.requestQueryString = null;
        }
    }

    public Config getConfig() {
        return exchange.connection.getServer().getConfig();
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

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseStatusText() {
        return responseStatusText;
    }

    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    public long getResponseBytesWritten() {
        return responseBytesWritten;
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
        return requestBodyMode == BodyMode.CHUNKED || requestBodyLength > 0;
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
                requestBody = new ChunkedInputStream(exchange.in, ChunkedInputStream.DEFAULT_MAX_CHUNK_SIZE,
                        getConfig().getMaximumRequestBodySize());
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
        validateHeader(name, value);
        if (isContentLength(name)) {
            setResponseContentLength(value);
        } else {
            responseHeaders.put(name, new ArrayList<>(Collections.singletonList(value)));
        }
        return this;
    }

    public HttpRequest addResponseHeader(String name, String value) {
        validateHeader(name, value);
        if (isContentLength(name)) {
            setResponseContentLength(value);
        } else {
            responseHeaders.computeIfAbsent(name, s -> new ArrayList<>()).add(value);
        }
        return this;
    }

    // content-length is written by beginResponse, so it is never sent twice or together with chunked
    static boolean isContentLength(String name) {
        return name.equalsIgnoreCase("content-length");
    }

    // header names and values are written as is, so a CR or LF would let them end the header line
    // and inject other headers or a body (response splitting), e.g. a redirect to a user supplied url
    static void validateHeader(String name, String value) {
        if (name == null || value == null) {
            throw new IllegalArgumentException("Header name and value must not be null: " + name);
        }
        if (hasLineBreak(name) || hasLineBreak(value)) {
            throw new IllegalArgumentException("Header must not contain CR or LF: " + name);
        }
    }

    private static boolean hasLineBreak(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\r' || c == '\n') {
                return true;
            }
        }
        return false;
    }

    // header names are stored as given, which for ring handlers is usually not lowercase
    public boolean hasResponseHeader(String name) {
        for (String key : responseHeaders.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    void setResponseContentLength(String value) {
        long length;
        try {
            length = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid content-length: " + value);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Negative content-length: " + value);
        }
        responseLength = length;
    }

    // RFC 9110: 1xx, 204 and 304 responses never have a body
    public static boolean isBodylessStatus(int status) {
        return status == 204 || status == 304 || (status >= 100 && status < 200);
    }


    public void skipBody() throws IOException {
        if (state == State.PENDING) {
            responseBody = false;
            beginResponse();
            state = HttpRequest.State.COMPLETE;
        } else {
            throw new IllegalStateException("can only skip body in pending state");
        }
    }

    public void respondNoContent() throws IOException {
        responseStatus = 204;
        skipBody();
    }

    public void respondNotModified() throws IOException {
        responseStatus = 304;
        skipBody();
    }

    public void writeString(String s) throws IOException {
        writeString(s, true);
    }

    // always UTF-8, use writeBytes for anything else
    public void writeString(String s, boolean isFinal) throws IOException {
        // Encode upfront: content-length must be the UTF-8 byte count, not s.length() (char count).
        // Non-ASCII chars use multiple bytes in UTF-8, so using char count truncates the response.
        writeBytes(s.getBytes(StandardCharsets.UTF_8), isFinal);
    }

    public void writeBytes(byte[] data) throws IOException {
        writeBytes(data, true);
    }

    public void writeBytes(byte[] data, boolean isFinal) throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            if (isFinal) {
                // the whole body is known, so this length is exact and wins over any content-length
                // set before. a wrong one would break keep-alive. unused when compressing, which chunks.
                responseLength = data.length;

                // don't compress small responses
                if (data.length < 850) {
                    autoCompress = false;
                }
            }
            beginResponse();
        }

        responseOut.write(data);
        responseOut.flush();

        if (isFinal) {
            responseOut.close();
        }
    }

    public void writeStream(InputStream in) throws IOException {
        writeStream(in, true);
    }

    public void writeStream(InputStream in, boolean isFinal) throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            beginResponse();
        }

        if (responseBody) {
            in.transferTo(responseOut);
        }
        responseOut.flush();

        if (isFinal) {
            responseOut.close();
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
        serveFile(file, file.getFileName().toString());
    }

    void serveFile(Path file, String filename) throws IOException {
        final Server server = exchange.connection.getServer();
        final FileTime lastModifiedTime = Files.getLastModifiedTime(file);
        final String lastModified = DATE_FORMATTER.format(lastModifiedTime.toInstant().atZone(GMT));

        final String ifModifiedSince = getRequestHeaderValue("if-modified-since");
        if (lastModified.equals(ifModifiedSince)) {
            respondNotModified();
        } else {
            long size = Files.size(file);

            // FIXME: maybe support range requests?

            // FIXME: don't do this per request. cache in FileInfo
            String mimeType = server.config.guessMimeType(filename);

            // FIXME: config option
            boolean compress = size >= 850 && server.config.isCompressible(mimeType);

            setResponseStatus(200);
            setResponseHeader("content-type", mimeType);

            if (compress) {
                autoCompress = true;
                autoChunk = true;
            } else {
                autoCompress = false;
                responseLength = size;
            }

            // FIXME: configurable caching options
            // this is soft-cache, allows using cache but forces client to check
            // replying with 304 as above, so we don't send body again
            // this isn't ideal, but this is not a production server and during
            // dev files may change often and we never want stale files (e.g. shadow-cljs JS outputs)
            setResponseHeader("cache-control", "private, no-cache");
            setResponseHeader("last-modified", lastModified);

            // HEAD requests get headers but not body
            if ("HEAD".equals(requestMethod)) {
                skipBody();
            } else {
                // using the outputBufferSize since we want to fill that asap, might as well do it all at once
                try (InputStream in = new BufferedInputStream(Files.newInputStream(file), server.config.outputBufferSize)) {
                    writeStream(in);
                }
            }
        }
    }

    private static final Pattern ENCODING_WEIGHT = Pattern.compile(
            "(gzip|identity|\\*)(?:[ \\t]*;[ \\t]*q=(0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?))?",
            Pattern.CASE_INSENSITIVE);

    private static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) return false;
        if ("gzip".equalsIgnoreCase(acceptEncoding)) return true;

        double gzip = -1;
        double wildcard = 0;
        double identity = 0;
        for (String entry : acceptEncoding.split(",")) {
            var match = ENCODING_WEIGHT.matcher(entry.trim());
            if (!match.matches()) continue;
            double weight = match.group(2) == null ? 1 : Double.parseDouble(match.group(2));
            switch (match.group(1).toLowerCase(Locale.ROOT)) {
                case "gzip" -> gzip = weight;
                case "*" -> wildcard = weight;
                case "identity" -> identity = weight;
            }
        }
        if (gzip < 0) gzip = wildcard;
        return gzip > 0 && gzip >= identity;
    }

    void beginResponse() throws IOException {
        if (state != State.PENDING) {
            throw new IllegalStateException("response already committed");
        }

        boolean head = "HEAD".equals(requestMethod);
        boolean bodylessStatus = isBodylessStatus(responseStatus);
        if (!responseBody && !head && !bodylessStatus) {
            responseLength = 0;
        }
        if (head || bodylessStatus) {
            responseBody = false;
        }
        writeStatusLine(responseStatus, responseStatusText);

        if (responseBody && autoCompress) {
            // content the handler already encoded (e.g. pre-gzipped) must not be compressed again
            autoCompress = !hasResponseHeader("content-encoding")
                    && acceptsGzip(getRequestHeaderValue("accept-encoding"));
            if (autoCompress) {
                setResponseHeader("content-encoding", "gzip");
            }
        }

        // compressed length isn't known upfront, uncompressed length known so no need to chunk
        if (responseBody && autoCompress) {
            autoChunk = true;
        } else if (responseBody && responseLength >= 0) {
            autoChunk = false;
        }

        for (Map.Entry<String, List<String>> header : responseHeaders.entrySet()) {
            String name = header.getKey();

            for (String value : header.getValue()) {
                writeHeader(name, value);
            }
        }

        if (responseBody && autoChunk) {
            writeHeader("transfer-encoding", "chunked");
        } else if (responseLength >= 0 && responseStatus >= 200 && responseStatus != 204) {
            writeHeader("content-length", Long.toString(responseLength));
        }

        if (!closeAfter && "close".equals(getRequestHeaderValue("connection"))) {
            closeAfter = true;
        }

        if (closeAfter) {
            writeHeader("connection", "close");
        } else if ("HTTP/1.0".equals(requestVersion)) {
            writeHeader("connection", "keep-alive");
        }

        writeHeaderEnd();

        if (!responseBody) {
            exchange.out.flush();
            responseOut = new OutputStream() {
                @Override public void write(int b) {}
                @Override public void write(byte[] b, int off, int len) {}
                @Override public void close() { state = State.COMPLETE; }
            };
            state = State.BODY;
            return;
        }

        state = State.BODY;

        // handler code should not be in control of closing underlying out stream since we manage keep-alive handling
        // but closing is still useful to know if the response was actually properly finished
        responseOut = new InterceptedCloseOutputStream(this, exchange.out);

        if (autoChunk) {
            responseOut = new ChunkedOutputStream(responseOut);
        }

        // FIXME: if fixed contentLength known could wrap stream to ensure user sends correct amount

        if (autoCompress) {
            responseOut = new GZIPOutputStream(responseOut, 8192, true);
        }
    }

    /**
     * validates and extracts some data from request headers after parsing is finished
     */
    void prepare() throws BadRequestException {
        String transferEncoding = getRequestHeaderValue("transfer-encoding");
        String contentLengthHeader = getRequestHeaderValue("content-length");
        if (transferEncoding != null) {
            if (contentLengthHeader != null || !"HTTP/1.1".equals(requestVersion)
                    || !"chunked".equalsIgnoreCase(transferEncoding)) {
                throw new BadRequestException("Unsupported or conflicting request framing");
            }
            requestBodyMode = BodyMode.CHUNKED;
        } else if (contentLengthHeader != null) {
            long contentLength = parseContentLength(contentLengthHeader);

            if (contentLength > exchange.connection.getServer().getConfig().maximumRequestBodySize) {
                throw new BadRequestException("Request Content-Length exceeds maximum acceptable size: " + contentLength) ;
            }

            requestBodyLength = contentLength;
            requestBodyMode = BodyMode.FIXED_LENGTH;
        }
    }


    static long parseContentLength(String value) throws BadRequestException {
        if (value.isEmpty() || !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new BadRequestException("Invalid Content-Length");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid Content-Length", e);
        }
    }

    void checkComplete() {
        if (state == State.COMPLETE) {
            throw new IllegalStateException("HttpRequest already completed");
        }
    }

    void writeStatusLine(int statusCode, String reasonPhrase) throws IOException {
        // FIXME: what about http/1.0? what happens if we answer 1.1 to a 1.0 request? does anything actually use 1.0?
        exchange.out.write(HTTP11_START);

        // status-code = 3DIGIT
        exchange.out.write('0' + (statusCode / 100));
        exchange.out.write('0' + (statusCode / 10) % 10);
        exchange.out.write('0' + statusCode % 10);
        exchange.out.write(' ');

        if (reasonPhrase != null && !reasonPhrase.isEmpty()) {
            exchange.out.write(reasonPhrase.getBytes(StandardCharsets.US_ASCII));
        }

        exchange.out.write(CRLF);
    }

    void writeHeader(String name, String value) throws IOException {
        exchange.out.write(name.getBytes(StandardCharsets.US_ASCII));
        exchange.out.write(COLON_SP);
        exchange.out.write(value.getBytes(StandardCharsets.US_ASCII));
        exchange.out.write(CRLF);
    }

    void writeHeaderEnd() throws IOException {
        exchange.out.write(CRLF);
    }
}
