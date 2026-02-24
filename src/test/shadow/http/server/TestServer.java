package shadow.http.server;

import java.io.IOException;

public class TestServer {

    public static void main(String[] args) throws Exception {

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
        ClasspathHandler cp1 = ClasspathHandler.forPrefix("/shadow/cljs/ui/dist");
        ClasspathHandler cp2 = ClasspathHandler.forPrefix("/");

        Server server = new Server();
        server.setHandlers(files, cp1, cp2, test);
        server.start(5007);

        System.out.println("Server started on http://localhost:5007");
    }
}
