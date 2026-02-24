package shadow.http.server;

import java.io.IOException;

public interface HttpContext {
    void upgradeToWebSocket(WebSocketHandler handler) throws IOException;

    HttpResponse respond() throws IOException;

    boolean didRespond();
}
