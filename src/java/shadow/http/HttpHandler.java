package shadow.http;

import java.io.IOException;

public interface HttpHandler {

    void handle(HttpContext context, HttpRequest request) throws IOException;

    default void addedToServer(Server server) {
    }

    default void cleanup() {
    }
}
