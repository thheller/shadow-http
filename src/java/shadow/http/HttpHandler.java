package shadow.http;

import java.io.IOException;

// .close will be called on server shutdown or handle swap, not per request
public interface HttpHandler {
    /**
     * should return true if the request was handled and a response was sent
     * or false if the handler wants to pass, in which case the handler must not have sent anything in response
     */
    boolean handle(HttpContext context, HttpRequest request) throws IOException;

    default void addedToServer(Server server) {
    }

    default void cleanup() {
    }
}
