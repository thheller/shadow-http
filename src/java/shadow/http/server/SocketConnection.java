package shadow.http.server;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;

public class SocketConnection implements Connection, Runnable {

    final Server server;
    final Socket socket;

    InputStream socketIn;
    OutputStream socketOut;

    Exchange exchange;

    public SocketConnection(Server server, Socket socket) {
        this.server = server;
        this.socket = socket;
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

    @Override
    public boolean isActive() {
        return !socket.isClosed();
    }

    public void upgrade(Exchange next) {
        this.exchange = next;
    }

    @Override
    public void run() {
        try {
            this.socketIn = new BufferedInputStream(socket.getInputStream(), server.config.inputBufferSize);
            this.socketOut = new BufferedOutputStream(socket.getOutputStream(), server.config.outputBufferSize);

            // starts out as http, may upgrade into websocket, at which point we don't need http specifics
            // anymore, since there is no downgrade. so keeping everything http related in HttpExchange
            // allows that to be collected after things upgraded
            this.exchange = new HttpExchange(this);

            server.connectionStarted(this);

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
            // ignore, probably just closed
            // FIXME: check/log?
        } catch (IOException e) {
            // ignore, also probably socket just closed
            // FIXME: check/log?
        } catch (Exception e) {
            // FIXME: check/log?
            e.printStackTrace();
        }

        if (!socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // shutting down, don't care
            }
        }

        server.connectionCompleted(this);
    }
}
