package shadow.http.server;

import java.io.IOException;

public interface HttpHandler {
    void handle(HttpRequest request) throws IOException;
}
