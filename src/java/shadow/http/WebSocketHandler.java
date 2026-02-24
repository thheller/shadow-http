package shadow.http;

import java.io.IOException;

public interface WebSocketHandler {

    default WebSocketHandler start(WebSocketContext ctx) {
        return this;
    }
    default WebSocketHandler onText(WebSocketContext ctx, String payload) throws IOException {
        return this;
    }

    default WebSocketHandler onBinary(WebSocketContext ctx, byte[] payload) throws IOException {
        return this;
    }

    default void onClose(int statusCode, String reason) {
    }
}
