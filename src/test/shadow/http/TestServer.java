package shadow.http;

import java.io.IOException;

public class TestServer {

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
