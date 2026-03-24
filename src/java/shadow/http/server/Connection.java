package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;

public interface Connection {

    Server getServer();

    boolean isSecure();

    SocketAddress getRemoteAddress();

    InputStream getInputStream() throws IOException;
    OutputStream getOutputStream() throws IOException;

    void upgrade(Exchange next);

    boolean isActive();
}
