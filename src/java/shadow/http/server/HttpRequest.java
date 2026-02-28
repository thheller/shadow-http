package shadow.http.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
    public final String requestVersion;
    public final List<Header> requestHeadersInOrder = new ArrayList<>();
    public final Map<String, String> requestHeaders = new HashMap<>();

    BodyMode requestBodyMode = BodyMode.NONE;

    InputStream requestBody;
    long requestBodyLength;

    public State state = State.PENDING;

    int responseStatus = 200;
    String responseStatusText = null;
    public final Map<String, String> responseHeaders = new HashMap<>();
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

    public String getRequestVersion() {
        return requestVersion;
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

            exchange.connection.upgrade(new WebSocketExchange(exchange.connection, handler, pmd));
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
        responseHeaders.put(name, value);
        return this;
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
        responseStatus = 304;
        skipBody();
    }

    public void writeString(String s) throws IOException {
        writeString(s, true);
    }

    public void writeString(String s, boolean isFinal) throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            if (isFinal) {
                // don't compress small responses
                int length = s.length();
                if (length < 850 || !autoCompress) {
                    responseLength = length;
                    autoCompress = false;
                    autoChunk = false;
                } else {
                    responseLength = length;
                    autoChunk = false;
                }
            }
            beginResponse();
        }

        // FIXME: actually respect contentChartset
        responseOut.write(s.getBytes(StandardCharsets.UTF_8));
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

        in.transferTo(responseOut);
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

        responseOut = exchange.out;

        writeStatusLine(responseStatus, responseStatusText);

        if (responseBody && autoCompress) {
            String acceptEncoding = getRequestHeaderValue("accept-encoding");

            // FIXME: not sure if worth adding dependencies to get zstd or brotli
            // only gzip is fine for now
            if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
                autoCompress = false;
            } else {
                setResponseHeader("content-encoding", "gzip");
            }
        }

        for (Map.Entry<String, String> header : responseHeaders.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();

            writeHeader(name, value);
        }

        if (responseBody && autoChunk) {
            writeHeader("transfer-encoding", "chunked");
        } else if (responseLength >= 0) {
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
            responseOut.flush();
            responseOut = null;
            responseBytesWritten = 0;
            state = State.COMPLETE;
            return;
        }

        state = State.BODY;

        // handler code should not be in control of closing underlying out stream since we manage keep-alive handling
        // but closing is still useful to know if the response was actually properly finished
        responseOut = new InterceptedCloseOutputStream(this, responseOut);

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

    void writeStatusLine(int statusCode, String reasonPhrase) throws IOException {
        // FIXME: what about http/1.0? what happens if we answer 1.1 to a 1.0 request? does anything actually use 1.0?
        responseOut.write(HTTP11_START);

        // status-code = 3DIGIT
        responseOut.write('0' + (statusCode / 100));
        responseOut.write('0' + (statusCode / 10) % 10);
        responseOut.write('0' + statusCode % 10);
        responseOut.write(' ');

        if (reasonPhrase != null && !reasonPhrase.isEmpty()) {
            responseOut.write(reasonPhrase.getBytes(StandardCharsets.US_ASCII));
        }

        responseOut.write(CRLF);
    }

    void writeHeader(String name, String value) throws IOException {
        responseOut.write(name.getBytes(StandardCharsets.US_ASCII));
        responseOut.write(COLON_SP);
        responseOut.write(value.getBytes(StandardCharsets.US_ASCII));
        responseOut.write(CRLF);
    }

    void writeHeaderEnd() throws IOException {
        responseOut.write(CRLF);
    }
}
