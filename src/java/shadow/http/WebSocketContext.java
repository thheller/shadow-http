package shadow.http;

import java.io.IOException;

public interface WebSocketContext {

    // thread-safe version
    void sendFrame(WebSocketFrame frame) throws IOException;

    // version to use if single writer, bypasses the lock
    void sendFrameEx(WebSocketFrame frame) throws IOException;

    void close(int statusCode) throws IOException;
}
