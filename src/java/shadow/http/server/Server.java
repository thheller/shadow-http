package shadow.http.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Config config;

    private HttpHandler[] handlers = null;
    private ServerSocket socket = null;
    private Thread acceptorThread = null;

    public Server() {
        this(new Config());
    }

    public Server(Config config) {
        this.config = config;
    }

    public void setHandler(HttpHandler handler) {
        setHandlers(handler);
    }

    public void setHandlers(HttpHandler... handlers) {
        if (handlers == null || handlers.length == 0) {
            throw new IllegalArgumentException("can't take no handlers");
        }

        if (this.handlers != null) {
            for (HttpHandler handler : this.handlers) {
                handler.cleanup();
            }
        }

        this.handlers = new HttpHandler[handlers.length];
        for (int i = 0; i < handlers.length; i++) {
            HttpHandler handler = handlers[i];
            this.handlers[i] = handler.addedToServer(this);
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
        start("0.0.0.0", port);
    }

    public void start(String host, int port) throws IOException {
        socket = new ServerSocket();

        // allow immediate restart without waiting for TIME_WAIT to expire
        socket.setReuseAddress(true);

        socket.bind(new InetSocketAddress(host, port));

        acceptorThread = new Thread(new Acceptor(this), "shadow.http.server[accept-loop:" + port + "]");
        acceptorThread.start();
    }

    public ServerSocket getSocket() {
        return socket;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public Config getConfig() {
        return config;
    }

    public void stop() throws IOException, InterruptedException {
        socket.close();

        List<Runnable> remaining = executor.shutdownNow();

        if (!remaining.isEmpty()) {
            // FIXME: do something?
        }

        for (HttpHandler handler : handlers) {
            handler.cleanup();
        }
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
            } catch (IOException e) {
                e.printStackTrace();
            }

            server.acceptorThread = null;
        }
    }
}
