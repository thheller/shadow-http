package shadow.http;

import java.io.IOException;

public interface WebSocketContext {
    void sendText(String text) throws IOException;

    /*

    void sendBinary(byte[] bytes, int offset, int length) throws IOException;

    default void sendBinary(byte[] bytes) throws IOException {
        sendBinary(bytes, 0, bytes.length);
    }

    void sendBinary(ByteBuffer buf) throws IOException;

    void sendBinary(InputStream in) throws IOException;

     */

    void sendClose(int statusCode) throws IOException;
}
