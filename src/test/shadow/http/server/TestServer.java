package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class TestServer {

    public static void main(String[] args) throws Exception {

        HttpHandler test = (request) -> {
            if (request.target.equals("/")) {
                request.respond().setStatus(200).setContentType("text/plain").writeString("ok!");
            } else if (request.target.equals("/ws")) {
                request.upgradeToWebSocket(new WebSocketHandler.Base() {
                    @Override
                    public WebSocketHandler onText(String payload) throws IOException {
                        context.sendText(payload);
                        return this;
                    }
                });
            } else if (request.target.equals("/upload")) {
                try (InputStream body = request.body()) {
                    try (OutputStream out = request.respond().setStatus(200).setCompress(true).setChunked(true).setContentType("text/plain").body()) {
                        body.transferTo(out);
                    }
                }
            } else if (request.target.equals("/sse")) {

                HttpResponse response = request.respond()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setChunked(true)
                        .setBody(true)
                        .setCompress(true);

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
