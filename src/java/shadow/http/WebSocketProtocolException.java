package shadow.http;

import java.io.IOException;

/**
 * Protocol exception carrying a WebSocket close status code (Section 7.4).
 */
public class WebSocketProtocolException extends IOException {
    private final int statusCode;

    public WebSocketProtocolException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    /**
     * The appropriate WebSocket close status code (Section 7.4.1) for this error.
     */
    public int getStatusCode() {
        return statusCode;
    }
}
