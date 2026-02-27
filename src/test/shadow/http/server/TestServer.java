package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class TestServer {

    public static void main(String[] args) throws Exception {

        HttpHandler test = (request) -> {
            if (request.requestTarget.equals("/")) {
                request.setResponseHeader("content-type", "text/plain").writeString("ok!");
            } else if (request.requestTarget.equals("/ws")) {
                request.upgradeToWebSocket(new WebSocketHandler.Base() {
                    @Override
                    public void onText(String payload) throws IOException {
                        context.sendText(payload);
                    }
                });
            } else if (request.requestTarget.equals("/upload")) {
                try (InputStream body = request.requestBody()) {
                    try (OutputStream out = request.setResponseHeader("content-type", "text/plain").responseBody()) {
                        body.transferTo(out);
                    }
                }
            } else if (request.requestTarget.equals("/sse")) {
                request.setResponseHeader("content-type", "text/event-stream");

                while (true) {
                    request.writeString("data: " + System.currentTimeMillis() + "\n\n", false);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        };

        HttpHandler files = FileHandler.forPath("docs");
        ClasspathHandler cp1 = ClasspathHandler.forPrefix("/shadow/cljs/ui/dist");
        ClasspathHandler cp2 = ClasspathHandler.forPrefix("/");

        Server server = new Server();
        server.setHandlers(files, cp1, cp2, test);
        server.start(5007);

        System.out.println("Server started on http://localhost:5007");
    }
}
