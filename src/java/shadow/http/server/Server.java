package shadow.http.server;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final Config config;

    private HttpHandler handler = null;
    private Acceptor acceptor = null;
    private ServerSocket socket = null;

    public Server() {
        this(Config.DEFAULT);
    }

    public Server(Config config) {
        this.config = config;
    }

    void handle(HttpRequest request) throws IOException {
        handler.handle(request);
    }

    public void start(int port) throws IOException {
        start("0.0.0.0", port);
    }

    public void start(String host, int port) throws IOException {
        if (acceptor != null) {
            throw new IllegalStateException("server already listening. create new server instance if you need multiple endpoints.");
        }
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host, port));

        Acceptor acc = new Acceptor(this, socket);
        acc.start();

        this.acceptor = acc;
    }

    public void startSSL(SSLContext ctx, int port) throws IOException {
        startSSL(ctx, "0.0.0.0", port);
    }

    public void startSSL(SSLContext ctx, String host, int port) throws IOException {
        if (acceptor != null) {
            throw new IllegalStateException("server already listening. create new server instance if you need multiple endpoints.");
        }
        socket = ctx.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(host, port));

        Acceptor acc = new Acceptor(this, socket);
        acc.start();

        this.acceptor = acc;
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

    public void setHandler(HttpHandler handler) {
        this.handler = handler;
    }

    public void stop() throws IOException, InterruptedException {
        if (acceptor != null) {
            acceptor.socket.close();
        }

        List<Runnable> remaining = executor.shutdownNow();

        if (!remaining.isEmpty()) {
            // FIXME: do something?
        }
    }

    public void join() throws InterruptedException {
        if (acceptor == null) {
            throw new IllegalStateException("not started");
        }
        acceptor.thread.join();
    }

    void connectionStarted(SocketConnection connection) {
    }

    void connectionCompleted(SocketConnection connection) {
    }

    private static class Acceptor implements Runnable {

        private final Server server;
        private final ServerSocket socket;
        private final Thread thread;

        public Acceptor(Server server, ServerSocket socket) {
            this.server = server;
            this.socket = socket;
            this.thread = new Thread(this, "shadow.http.server[accept-loop:" + socket.getLocalPort() + "]");
        }

        void start() {
            thread.start();
        }

        @Override
        public void run() {
            try {
                while (!socket.isClosed()) {
                    Socket client = socket.accept();
                    server.executor.execute(new SocketConnection(server, client));
                }
            } catch (SocketException e) {
                // ignore, most likely closed
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static SSLContext sslContextForFile(String pathToFile, String password) throws Exception {
        char[] pwa = password.toCharArray();

        KeyStore keyStore = KeyStore.getInstance(new File(pathToFile), pwa);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, pwa);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }
}
