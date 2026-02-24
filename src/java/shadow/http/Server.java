package shadow.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Config config = new Config();

    private HttpHandler[] handlers = null;

    public void setHandler(HttpHandler handler) {
        setHandlers(handler);
    }

    public void setHandlers(HttpHandler... handlers) {
        HttpHandler[] prev = this.handlers;

        this.handlers = handlers;
        for (HttpHandler handler : handlers) {
            handler.addedToServer(this);
        }

        if (prev != null) {
            for (HttpHandler httpHandler : prev) {
                httpHandler.cleanup();
            }
        }
    }

    public void setHandlers(List<HttpHandler> handlers) {
        setHandlers((HttpHandler[]) handlers.toArray());
    }

    void handle(HttpContext ctx, HttpRequest request) throws IOException {
        HttpHandler[] current = handlers;

        for (HttpHandler handler : current) {
            handler.handle(ctx, request);

            if (ctx.didRespond()) {
                break;
            }
        }
    }

    public void start() throws IOException {
        ServerSocket server = new ServerSocket(5007);

        while (true) {
            Socket socket = server.accept();
            executor.execute(new SocketConnection(this, socket));
        }
    }

    public static void main(String[] args) throws IOException {

        HttpHandler test = (ctx, request) -> {
            if (request.target.equals("/")) {
                ctx.respond().setStatus(200).setContentType("text/plain").writeString("ok!");
            } else if (request.target.equals("/ws")) {
                ctx.upgradeToWebSocket(new WebSocketHandler() {
                    @Override
                    public WebSocketHandler onText(WebSocketContext ctx, String payload) throws IOException {
                        ctx.sendText(payload);
                        return this;
                    }
                });
            } else if (request.target.equals("/sse")) {

                HttpResponse response = ctx.respond()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setChunked(true)
                        .setBody(true)
                        .setCompress(false); // doesn't work currently. needs forced gzip flush.

                while (true) {
                    response.writeString("data: " + System.currentTimeMillis() + "\n\n", false);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        };

        HttpHandler files = FileHandler.forPath("docs").findFiles().watch();

        Server server = new Server();
        server.setHandlers(files, test);
        server.start();
    }
}
