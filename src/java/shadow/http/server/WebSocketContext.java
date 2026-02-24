package shadow.http.server;

import java.io.IOException;

public interface WebSocketContext {

    boolean isOpen();

    void sendText(String text) throws IOException;

    /*

    void sendBinary(byte[] bytes, int offset, int length) throws IOException;

    default void sendBinary(byte[] bytes) throws IOException {
        sendBinary(bytes, 0, bytes.length);
    }

    void sendBinary(ByteBuffer buf) throws IOException;

    void sendBinary(InputStream in) throws IOException;

     */

    void sendPing(byte[] payload) throws IOException;

    default void sendPing() throws IOException {
        sendPing(new byte[0]);
    }

    void sendPong(byte[] payload) throws IOException;

    default void sendPong() throws IOException {
        sendPong(new byte[0]);
    }

    void sendClose(int statusCode) throws IOException;
}
