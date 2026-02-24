package shadow.http;

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
    HttpResponse response;

    boolean upgraded = false;

    public HttpExchange(Connection connection) throws IOException {
        this.connection = connection;
        this.in = connection.getInputStream();
        this.out = connection.getOutputStream();
        this.httpIn = new HttpInput(in);
    }

    public void upgradeToWebSocket(WebSocketHandler handler) throws IOException {
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

            upgradeResponse.noContent();

            this.connection.upgrade(new WebSocketExchange(this.connection, handler, pmd));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        this.upgraded = true;
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
        return request != null && response != null;
    }

    @Override
    public void process() throws IOException {
        try {
            for (; ; ) {
                HttpRequest request = this.request = httpIn.readRequest();

                connection.getServer().handle(this, request);

                if (!didRespond()) {
                    respond().setStatus(404)
                            .setContentType("text/plain")
                            .writeString("Not found.");
                }

                HttpResponse res = this.response;

                if (res == null) {
                    throw new IllegalStateException("some handler pretended to handle request but didn't!");
                }

                if (res.state != HttpResponse.State.COMPLETE) {
                    throw new IllegalStateException("request not actually completed");
                }

                this.request = null;
                this.response = null;

                if (upgraded || res.closeAfter) {
                    break;
                }
            }
        } catch (EOFException e) {
            // ignore
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
