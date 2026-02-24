package shadow.http.server;

import java.io.IOException;

public interface HttpHandler {

    void handle(HttpContext context, HttpRequest request) throws IOException;

    default HttpHandler addedToServer(Server server) {
        return this;
    }

    default void cleanup() {
    }
}
