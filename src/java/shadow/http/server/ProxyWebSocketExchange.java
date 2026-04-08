package shadow.http.server;

import java.io.*;
import java.net.Socket;

/**
 * A transparent WebSocket proxy exchange that forwards raw bytes bidirectionally
 * between the client and the upstream server after a successful 101 upgrade.
 *
 * Unlike {@link WebSocketExchange}, this does not parse or interpret WebSocket
 * frames — it simply forwards them as-is in both directions.
 */
public class ProxyWebSocketExchange implements Exchange {

    private final Connection connection;
    private final InputStream clientIn;
    private final Socket upstreamSocket;
    private final InputStream upstreamIn;

    /**
     * @param connection     the client connection
     * @param clientIn       the client input stream (HttpInput from the exchange, which may have buffered data)
     * @param upstreamSocket the upstream socket (caller must not close it; this exchange owns it)
     * @param upstreamIn     the upstream input stream (HttpInput used for handshake, which may have buffered data)
     */
    public ProxyWebSocketExchange(Connection connection, InputStream clientIn, Socket upstreamSocket, InputStream upstreamIn) {
        this.connection = connection;
        this.clientIn = clientIn;
        this.upstreamSocket = upstreamSocket;
        this.upstreamIn = upstreamIn;
    }

    @Override
    public void process() throws IOException {
        try {
            OutputStream upstreamOut = upstreamSocket.getOutputStream();
            OutputStream clientOut = connection.getOutputStream();

            // Forward upstream → client in a virtual thread
            Thread upstream2client = Thread.ofVirtual().start(() -> {
                try {
                    pipe(upstreamIn, clientOut);
                } catch (IOException ignored) {
                    // upstream closed or client write failed
                } finally {
                    closeQuietly();
                }
            });

            // Forward client → upstream in this thread
            try {
                pipe(clientIn, upstreamOut);
            } catch (IOException ignored) {
                // client closed or upstream write failed
            } finally {
                closeQuietly();
            }

            upstream2client.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly();
        }
    }

    /**
     * Copy bytes from in to out, flushing after every read.
     * This ensures low-latency forwarding of WebSocket frames.
     */
    private static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    private void closeQuietly() {
        try {
            if (!upstreamSocket.isClosed()) {
                upstreamSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
