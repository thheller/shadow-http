package shadow.http.server;

import java.io.IOException;
import java.util.List;

/**
 * construct a handler that tries given handlers in order until one opts to process the request.
 */
public class HandlerList implements HttpHandler {
    private HttpHandler[] handlers = null;

    public HandlerList(HttpHandler[] handlers) {
        setHandlers(handlers);
    }

    public void setHandlers(HttpHandler... handlers) {
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("can't take no handlers");
        }

        this.handlers = handlers;
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        // maintain local in case handlers is swapped out
        HttpHandler[] current = handlers;

        for (HttpHandler handler : current) {
            handler.handle(request);

            if (request.isCommitted()) {
                break;
            }
        }
    }

    public static HandlerList create(HttpHandler... handlers) {
        return new HandlerList(handlers);
    }

    public static HandlerList create(List<HttpHandler> handlers) {
        return new HandlerList(handlers.toArray(new HttpHandler[0]));
    }
}
