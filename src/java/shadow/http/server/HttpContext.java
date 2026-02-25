package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;

public interface HttpContext {
    void upgradeToWebSocket(WebSocketHandler handler) throws IOException;

    void upgradeToWebSocket(WebSocketHandler handler, String subProtocol) throws IOException;

    boolean requestHasBody();

    InputStream requestBody() throws IOException;

    HttpResponse respond() throws IOException;

    boolean didRespond();
}
