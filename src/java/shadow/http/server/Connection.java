package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface Connection {

    Server getServer();

    InputStream getInputStream() throws IOException;
    OutputStream getOutputStream() throws IOException;

    void upgrade(Exchange next);

    boolean isActive();
}
