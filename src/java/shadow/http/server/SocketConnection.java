package shadow.http.server;

import javax.net.ssl.SSLSocket;
import java.io.*;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

public class SocketConnection implements Connection, Runnable {

    final Server server;
    final Socket socket;

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
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    public boolean isSecure() {
        return socket instanceof SSLSocket;
    }

    public SocketAddress getRemoteAddress() {
        return socket.getRemoteSocketAddress();
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
            // disable Nagle's algorithm - we already buffer at the application level
            // with BufferedOutputStream, so Nagle just adds unnecessary latency on flush
            socket.setTcpNoDelay(true);

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
