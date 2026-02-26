package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class HttpRequest {
    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static String computeWebSocketAcceptKey(String wsKey) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((wsKey + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    }

    final HttpExchange exchange;

    public final String method;
    public final String target;
    public final String httpVersion;
    public final List<Header> headersInOrder;
    public final Map<String,String> headers;

    InputStream requestBody;
    HttpResponse response;

    public HttpRequest(HttpExchange exchange, String method, String requestTarget, String httpVersion) {
        this.exchange = exchange;
        this.method = method;
        this.target = requestTarget;
        this.httpVersion = httpVersion;
        this.headersInOrder = new ArrayList<Header>();
        this.headers = new HashMap<>();
    }

    public String getHeaderValue(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    // the assumption is that the client is a browser, and they usually do not send duplicate headers
    // but if something actually needs to check it can
    public boolean hasRepeatedHeaders() {
        return headers.size() != headersInOrder.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(' ').append(httpVersion).append("\r\n");
        for (Header h : headersInOrder) {
            sb.append(h.nameIn).append(": ").append(h.value).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public String getMethod() {
        return method;
    }

    public String getTarget() {
        return target;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public List<Header> getHeadersInOrder() {
        return headersInOrder;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void upgradeToWebSocket(WebSocketHandler handler) throws IOException {
        upgradeToWebSocket(handler, null);
    }

    public void upgradeToWebSocket(WebSocketHandler handler, String subProtocol) throws IOException {
        // Validate required headers per RFC 6455 Section 4.2.1
        String upgrade = getHeaderValue("upgrade");
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            throw new IllegalStateException("Missing or invalid Upgrade header");
        }

        String connection = getHeaderValue("connection");
        if (connection == null || !connection.toLowerCase().contains("upgrade")) {
            throw new IllegalStateException("Missing or invalid Connection header");
        }

        String wsKey = getHeaderValue("sec-websocket-key");
        if (wsKey == null || wsKey.isEmpty()) {
            throw new IllegalStateException("Missing Sec-WebSocket-Key header");
        }

        String wsVersion = getHeaderValue("sec-websocket-version");
        if (!"13".equals(wsVersion)) {
            throw new IllegalStateException("Unsupported WebSocket version: " + wsVersion);
        }

        // Compute Sec-WebSocket-Accept per RFC 6455 Section 4.2.2
        // Concatenate key with magic GUID, SHA-1 hash, then base64 encode
        try {
            String acceptKey = computeWebSocketAcceptKey(wsKey);

            // Attempt to negotiate permessage-deflate per RFC 7692 Section 5
            String extensionsHeader = getHeaderValue("sec-websocket-extensions");
            PerMessageDeflate pmd = PerMessageDeflate.negotiate(extensionsHeader);

            HttpResponse upgradeResponse = respond().setStatus(101)
                    .setHeader("connection", "Upgrade")
                    .setHeader("upgrade", "websocket")
                    .setHeader("sec-websocket-accept", acceptKey);

            if (pmd != null) {
                upgradeResponse.setHeader("sec-websocket-extensions", pmd.buildResponseHeaderValue());
            }

            // Set Sec-WebSocket-Protocol if a subprotocol was selected per RFC 6455 Section 4.2.2 step 5
            if (subProtocol != null && !subProtocol.isEmpty()) {
                upgradeResponse.setHeader("sec-websocket-protocol", subProtocol);
            }

            upgradeResponse.noContent();

            exchange.connection.upgrade(new WebSocketExchange(exchange.connection, handler, pmd));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        exchange.upgraded = true;
    }

    /**
     * Returns true if the current request is expected to carry a message body,
     * per RFC 9112 Section 6 / 6.3:
     *
     *   "The presence of a message body in a request is signaled by a
     *    Content-Length or Transfer-Encoding header field."
     *
     * A Transfer-Encoding header always signals a body (the body length is
     * determined by the encoding itself, e.g. chunked).
     *
     * A Content-Length header signals a body only when its value is greater
     * than zero; Content-Length: 0 explicitly means no content.
     */
    public boolean hasBody() {

        // Transfer-Encoding takes precedence (RFC 9112 Section 6.3 rule 3 & 4).
        // Its mere presence signals that a body is being sent.
        if (hasHeader("transfer-encoding")) {
            return true;
        }

        // Content-Length without Transfer-Encoding (RFC 9112 Section 6.2 & 6.3 rule 5+).
        // A value of 0 means explicitly no body content.
        String contentLength = getHeaderValue("content-length");
        if (contentLength != null) {
            try {
                return Long.parseLong(contentLength.trim()) > 0;
            } catch (NumberFormatException e) {
                // Invalid Content-Length — treat conservatively as no body signal;
                // requestBody() will throw if actually called.
                return false;
            }
        }

        // No framing headers present — no body.
        return false;
    }

    public InputStream body() throws IOException {
        if (requestBody != null) {
            return requestBody;
        }

        String transferEncoding = getHeaderValue("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            return requestBody = new ChunkedInputStream(exchange.httpIn);
        }

        String contentLengthHeader = getHeaderValue("content-length");
        if (contentLengthHeader != null) {
            long contentLength;
            try {
                contentLength = Long.parseLong(contentLengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new IOException("Invalid Content-Length header: " + contentLengthHeader);
            }
            if (contentLength < 0) {
                throw new IOException("Negative Content-Length: " + contentLength);
            }
            return requestBody = new ContentLengthInputStream(exchange.in, contentLength);
        }

        throw new IOException("Request has no body (no Content-Length or Transfer-Encoding: chunked header)");
    }

    public HttpResponse respond() throws IOException {
        if (this.response != null) {
            throw new IllegalStateException("already responded");
        }
        return this.response = new HttpResponse(this, exchange.out);
    }

    public boolean didRespond() {
        return response != null;
    }
}
