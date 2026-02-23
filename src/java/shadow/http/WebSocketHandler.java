package shadow.http;

import java.io.IOException;

public interface WebSocketHandler {

    default WebSocketHandler start(WebSocketContext ctx) {
        return this;
    }

    default void stop() {
    }

    void handleFrame(WebSocketContext ctx, WebSocketFrame frame) throws IOException;
}
