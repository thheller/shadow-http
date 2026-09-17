package shadow.http.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the automatic "port already in use, try the next one" behavior of {@link Server}.
 */
public class PortRetryTest {

    private static final String HOST = "127.0.0.1";

    private final List<ServerSocket> occupied = new ArrayList<>();
    private final List<Server> servers = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (Server server : servers) {
            server.stop();
        }
        servers.clear();

        for (ServerSocket socket : occupied) {
            socket.close();
        }
        occupied.clear();
    }

    /**
     * binds count consecutive ports and keeps them bound until cleanup, returns the first port.
     */
    private int occupyConsecutivePorts(int count) throws IOException {
        // a few tries, in case something else grabs a port in the middle of the range
        for (int attempt = 0; attempt < 10; attempt++) {
            List<ServerSocket> taken = new ArrayList<>();

            try (ServerSocket probe = new ServerSocket()) {
                probe.bind(new InetSocketAddress(HOST, 0));
                int base = probe.getLocalPort();

                // probe must be closed before rebinding base, otherwise it counts as in use twice
                probe.close();

                for (int i = 0; i < count; i++) {
                    ServerSocket socket = new ServerSocket();
                    socket.setReuseAddress(true);
                    socket.bind(new InetSocketAddress(HOST, base + i));
                    taken.add(socket);
                }

                occupied.addAll(taken);
                return base;
            } catch (IOException e) {
                for (ServerSocket socket : taken) {
                    socket.close();
                }
            }
        }

        throw new IOException("could not reserve " + count + " consecutive ports");
    }

    private Server startServer(int port) throws IOException {
        Server server = new Server();
        server.setHandler((request) -> request.setResponseHeader("content-type", "text/plain").writeString("ok"));
        servers.add(server);
        server.start(HOST, port);
        return server;
    }

    @Test
    void usesNextPortWhenPortIsTaken() throws Exception {
        int base = occupyConsecutivePorts(1);

        Server server = startServer(base);

        assertEquals(base + 1, server.getSocket().getLocalPort());
    }

    @Test
    void skipsAllTakenPortsInARow() throws Exception {
        int base = occupyConsecutivePorts(3);

        Server server = startServer(base);

        assertEquals(base + 3, server.getSocket().getLocalPort());
    }

    @Test
    void servesRequestsOnTheFallbackPort() throws Exception {
        int base = occupyConsecutivePorts(1);

        Server server = startServer(base);
        int port = server.getSocket().getLocalPort();
        assertNotEquals(base, port);

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var response = client.send(
                HttpRequest.newBuilder(URI.create("http://" + HOST + ":" + port + "/"))
                        .timeout(Duration.ofMillis(500))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void throwsWhenTheEntireRetryRangeIsTaken() throws Exception {
        // the server tries the requested port plus 9 more
        int base = occupyConsecutivePorts(10);

        assertThrows(BindException.class, () -> startServer(base));
    }

    @Test
    void doesNotRetryWhenAskedForAnEphemeralPort() throws Exception {
        Server server = startServer(0);

        int port = server.getSocket().getLocalPort();
        assertTrue(port > 0, "expected the OS to assign a port");
    }

    @Test
    void secondServerOnTheSamePortMovesToTheNextOne() throws Exception {
        int base = occupyConsecutivePorts(1);
        occupied.remove(occupied.size() - 1).close();

        Server first = startServer(base);
        assertEquals(base, first.getSocket().getLocalPort());

        Server second = startServer(base);
        assertEquals(base + 1, second.getSocket().getLocalPort());
    }
}
