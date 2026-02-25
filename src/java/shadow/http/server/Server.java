package shadow.http.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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

        this.handlers = new HttpHandler[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            HttpHandler handler = handlers[i];
            this.handlers[i] = handler.addedToServer(this);
        }

        if (prev != null) {
            for (HttpHandler httpHandler : prev) {
                httpHandler.cleanup();
            }
        }
    }

    public void setHandlers(List<HttpHandler> handlers) {
        setHandlers(handlers.toArray(new HttpHandler[0]));
    }

    void handle(HttpRequest request) throws IOException {
        HttpHandler[] current = handlers;

        for (HttpHandler handler : current) {
            handler.handle(request);

            if (request.didRespond()) {
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

    // FIXME: collect stats, maybe hookup metrics?
    // not sure if actually worth for shadow-cljs, but might be interesting
    // there is potentially an absurd amount of data going over the wire
    // dev JS + source maps is probably several MB per page view
    void connectionStarted(SocketConnection connection) {
    }

    void connectionCompleted(SocketConnection connection) {
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
            } catch (SocketException e) {
                // ignore, most likely closed
            } catch (IOException e)  {
                e.printStackTrace();
            }

            server.acceptorThread = null;
        }
    }
}
