package shadow.http;

import java.io.IOException;
import java.util.List;

public class HttpHandlerChain implements HttpHandler {

    private final Step firstStep;

    HttpHandlerChain(Step firstStep) {
        this.firstStep = firstStep;
    }

    @Override
    public boolean handle(HttpContext ctx, HttpRequest request) throws IOException {
        Step step = firstStep;
        while (step != null) {
            if (step.handler.handle(ctx, request)) {
                return true;
            }
            step = step.next;
        }
        return false;
    }

    @Override
    public void addedToServer(Server server) {
        Step step = firstStep;
        while (step != null) {
            step.handler.addedToServer(server);
            step = step.next;
        }
    }

    public void cleanup() {
        Step step = firstStep;
        while (step != null) {
            step.handler.cleanup();
            step = step.next;
        }
    }

    public static HttpHandler fromList(List<HttpHandler> handlers) {
        if (handlers.size() == 1) {
            return handlers.getFirst();
        } else if (handlers.isEmpty()) {
            throw new IllegalArgumentException("need at least one handler");
        }
        Step current = null;
        for (HttpHandler h : handlers.reversed()) {
            current = new Step(current, h);
        }

        return new HttpHandlerChain(current);
    }

    public static HttpHandler forHandlers(HttpHandler... handlers) {
        return fromList(List.of(handlers));
    }

    private static class Step {
        final Step next;
        final HttpHandler handler;

        public Step(Step next, HttpHandler handler) {
            this.next = next;
            this.handler = handler;
        }
    }
}
