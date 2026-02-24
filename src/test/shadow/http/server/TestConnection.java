package shadow.http.server;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class TestConnection implements Connection {

    public static String run(HttpHandler handler, String rawRequest) throws IOException {
        Server server = new Server();
        server.setHandler(handler);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TestConnection con = new TestConnection(server, rawRequest, out);

        HttpExchange exchange = new HttpExchange(con);
        exchange.process();

        return out.toString(StandardCharsets.UTF_8);
    }

    final Server server;
    final InputStream in;
    final OutputStream out;

    public TestConnection(Server server, String input, OutputStream out) {
        this.server = server;
        this.in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        this.out = out;
    }

    public TestConnection(Server server, InputStream in, OutputStream out) {
        this.server = server;
        this.in = in;
        this.out = out;
    }

    Exchange upgraded = null;

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return in;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return out;
    }

    @Override
    public void upgrade(Exchange next) {
        this.upgraded = next;
    }
}
