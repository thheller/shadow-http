package shadow.http.server;

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
    private ServerSocket socket = null;
    private Thread acceptorThread = null;

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

    public void start(int port) throws IOException {
        socket = new ServerSocket(port);

        acceptorThread = new Thread(new Acceptor(this), "shadow.http.server[accept-loop:" + port + "]");
        acceptorThread.start();
    }

    public void stop() throws IOException, InterruptedException {
        socket.close();

        List<Runnable> remaining = executor.shutdownNow();

        if (!remaining.isEmpty()){
            // FIXME: do something?
        }

        join();
    }

    public void join() throws InterruptedException {
        if (this.acceptorThread == null) {
            throw new IllegalStateException("not started");
        }
        this.acceptorThread.join();
    }

    private static class Acceptor implements Runnable {

        private final Server server;

        public Acceptor(Server server) {
            this.server = server;
        }

        @Override
        public void run() {
            try {
                while (!server.socket.isClosed()) {
                    Socket socket = server.socket.accept();
                    server.executor.execute(new SocketConnection(server, socket));
                }
            } catch (IOException e)  {
                e.printStackTrace();
            }

            server.acceptorThread = null;
        }
    }
}
