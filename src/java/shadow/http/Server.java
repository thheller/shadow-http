package shadow.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    HttpHandler rootHandler;
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Config config = new Config();

    public void setHandler(HttpHandler handler) {
        HttpHandler prev = rootHandler;

        rootHandler = handler;
        rootHandler.addedToServer(this);
        if (prev != null) {
            prev.cleanup();
        }
    }

    public void setHandlers(HttpHandler... handlers) {
        setHandler(HttpHandlerChain.fromList(List.of(handlers)));
    }
    public void setHandlers(List<HttpHandler> chain) {
        setHandler(HttpHandlerChain.fromList(chain));
    }

    boolean handle(HttpContext ctx, HttpRequest request) throws IOException {
        return rootHandler.handle(ctx, request);
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
                return true;
            }
            return false;
        };

        HttpHandler files = FileHandler.forPath("docs").findFiles().watch();

        Server server = new Server();
        server.setHandlers(files, test);
        server.start();
    }
}
