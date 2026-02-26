package shadow.http.server;

import java.io.IOException;

public interface WebSocketHandler {

    default WebSocketHandler start(WebSocketConnection ctx) {
        return this;
    }
    default void onText(String payload) throws IOException {
    }

    default void onBinary(byte[] payload) throws IOException {
    }

    void onPing(byte[] payload) throws IOException;

    default void onPong(byte[] payload) throws IOException {
    }

    default void onClose(int statusCode, String reason) {
    }

    class Base implements WebSocketHandler {
        protected WebSocketConnection context;

        public Base() {
        }

        @Override
        public WebSocketHandler start(WebSocketConnection ctx) {
            this.context = ctx;
            return this;
        }

        @Override
        public void onText(String payload) throws IOException {
        }

        @Override
        public void onBinary(byte[] payload) throws IOException {
        }

        @Override
        public void onPing(byte[] payload) throws IOException {
            context.sendPong(payload);
        }

        @Override
        public void onPong(byte[] payload) throws IOException {
        }

        @Override
        public void onClose(int statusCode, String reason) {
        }
    }
}
