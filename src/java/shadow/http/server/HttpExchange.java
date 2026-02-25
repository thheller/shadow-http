package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public class HttpExchange implements Exchange, HttpContext {
    public static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static String computeWebSocketAcceptKey(String wsKey) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((wsKey + WEBSOCKET_GUID).getBytes(StandardCharsets.US_ASCII));
        return Base64.getEncoder().encodeToString(hash);
    }

    final Connection connection;
    final InputStream in;
    final OutputStream out;
    final HttpInput httpIn;

    HttpRequest request;
    InputStream requestBody;

    HttpResponse response;

    final long since;

    boolean upgraded = false;

    public HttpExchange(Connection connection) throws IOException {
        this.since = System.currentTimeMillis();
        this.connection = connection;
        this.in = connection.getInputStream();
        this.out = connection.getOutputStream();
        this.httpIn = new HttpInput(in);
    }

    public void upgradeToWebSocket(WebSocketHandler handler) throws IOException {
        upgradeToWebSocket(handler, null);
    }

    public void upgradeToWebSocket(WebSocketHandler handler, String subProtocol) throws IOException {
        // Validate required headers per RFC 6455 Section 4.2.1
        String upgrade = request.getHeaderValue("upgrade");
        if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) {
            throw new IllegalStateException("Missing or invalid Upgrade header");
        }

        String connection = request.getHeaderValue("connection");
        if (connection == null || !connection.toLowerCase().contains("upgrade")) {
            throw new IllegalStateException("Missing or invalid Connection header");
        }

        String wsKey = request.getHeaderValue("sec-websocket-key");
        if (wsKey == null || wsKey.isEmpty()) {
            throw new IllegalStateException("Missing Sec-WebSocket-Key header");
        }

        String wsVersion = request.getHeaderValue("sec-websocket-version");
        if (!"13".equals(wsVersion)) {
            throw new IllegalStateException("Unsupported WebSocket version: " + wsVersion);
        }

        // Compute Sec-WebSocket-Accept per RFC 6455 Section 4.2.2
        // Concatenate key with magic GUID, SHA-1 hash, then base64 encode
        try {
            String acceptKey = computeWebSocketAcceptKey(wsKey);

            // Attempt to negotiate permessage-deflate per RFC 7692 Section 5
            String extensionsHeader = request.getHeaderValue("sec-websocket-extensions");
            WebSocketCompression pmd = WebSocketCompression.negotiate(extensionsHeader);

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

            this.connection.upgrade(new WebSocketExchange(this.connection, handler, pmd));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        this.upgraded = true;
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
    @Override
    public boolean requestHasBody() {
        if (request == null) {
            throw new IllegalStateException("No active request");
        }

        // Transfer-Encoding takes precedence (RFC 9112 Section 6.3 rule 3 & 4).
        // Its mere presence signals that a body is being sent.
        if (request.hasHeader("transfer-encoding")) {
            return true;
        }

        // Content-Length without Transfer-Encoding (RFC 9112 Section 6.2 & 6.3 rule 5+).
        // A value of 0 means explicitly no body content.
        String contentLength = request.getHeaderValue("content-length");
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

    @Override
    public InputStream requestBody() throws IOException {
        if (request == null) {
            throw new IllegalStateException("No active request");
        }

        if (requestBody != null) {
            return requestBody;
        }

        String transferEncoding = request.getHeaderValue("transfer-encoding");
        if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
            return requestBody = new ChunkedInputStream(httpIn);
        }

        String contentLengthHeader = request.getHeaderValue("content-length");
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
            return requestBody = new ContentLengthInputStream(in, contentLength);
        }

        throw new IOException("Request has no body (no Content-Length or Transfer-Encoding: chunked header)");
    }

    @Override
    public HttpResponse respond() throws IOException {
        if (this.response != null) {
            throw new IllegalStateException("already responded");
        }
        return this.response = new HttpResponse(this);
    }

    @Override
    public boolean didRespond() {
        return response != null;
    }

    @Override
    public void process() throws IOException {
        for (; ; ) {

            try {
                request = httpIn.readRequest();
            } catch (BadRequestException e) {
                respond().setStatus(400).setContentType("text/plain").setCloseAfter(true).writeString(e.getMessage());
                break;
            } catch (EOFException e) {
                break;
            }

            connection.getServer().handle(this, request);

            if (!didRespond()) {
                respond().setStatus(404)
                        .setContentType("text/plain")
                        .writeString("Not found.");
            }

            if (requestHasBody()) {
                // FIXME: point is to entirely drain the request body
                // or should it be an error if the handler didn't do that?
                requestBody().close();
            }

            if (response.state != HttpResponse.State.COMPLETE) {
                throw new IllegalStateException("request not actually completed");
            }

            HttpResponse res = response;

            request = null;
            requestBody = null;
            response = null;

            if (upgraded || res.closeAfter) {
                break;
            }
        }
    }
}
