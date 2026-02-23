package shadow.http;

import java.io.*;

public interface Connection {

    Server getServer();

    InputStream getInputStream() throws IOException;
    OutputStream getOutputStream() throws IOException;

    void upgrade(Exchange next);
}
