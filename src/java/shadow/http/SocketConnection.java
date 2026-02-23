package shadow.http;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class SocketConnection implements Connection, Runnable {

    final Server server;
    final Socket socket;
    final InputStream socketIn;
    final OutputStream socketOut;

    Exchange exchange;

    public SocketConnection(Server server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;

        // FIXME: buffer sizes should be configurable
        this.socketIn = new BufferedInputStream(socket.getInputStream(), 8192);
        this.socketOut = new BufferedOutputStream(socket.getOutputStream(), 65536);

        // starts out as http, may upgrade into websocket, at which point we don't need http specifics
        // anymore, since there is no downgrade. so keeping everything http related in HttpExchange
        // allows that to be collected after things upgraded
        this.exchange = new HttpExchange(this);
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return socketIn;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return socketOut;
    }

    public void upgrade(Exchange next) {
        this.exchange = next;
    }

    @Override
    public void run() {
        try {
            for (; ; ) {
                if (exchange != null) {
                    Exchange active = exchange;

                    active.process();

                    // we do not process the same exchange again
                    // only loop again if ex upgraded to a new impl
                    if (active == exchange) {
                        break;
                    }
                }
            }
        } catch (SocketException e) {
            // ignore
        } catch (IOException e) {
            // ignore
            // FIXME: log?
        }

        if (!socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // shutting down, don't care
            }
        }
    }
}
