package shadow.http.server;

import java.io.IOException;

public interface WebSocketHandler {

    default WebSocketHandler start(WebSocketConnection ctx) {
        return this;
    }
    default WebSocketHandler onText(String payload) throws IOException {
        return this;
    }

    default WebSocketHandler onBinary(byte[] payload) throws IOException {
        return this;
    }

    WebSocketHandler onPing(byte[] payload) throws IOException;

    default WebSocketHandler onPong(byte[] payload) throws IOException {
        return this;
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
        public WebSocketHandler onText(String payload) throws IOException {
            return this;
        }

        @Override
        public WebSocketHandler onBinary(byte[] payload) throws IOException {
            return this;
        }

        @Override
        public WebSocketHandler onPing(byte[] payload) throws IOException {
            context.sendPong(payload);
            return this;
        }

        @Override
        public WebSocketHandler onPong(byte[] payload) throws IOException {
            return this;
        }

        @Override
        public void onClose(int statusCode, String reason) {
        }
    }
}
