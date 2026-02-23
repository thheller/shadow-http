package shadow.http;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class TestConnection implements Connection {
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
