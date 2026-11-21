package shadow.http.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ProxyHandler} preserves the path of the configured target URI.
 *
 * <p>An upstream Server echoes back the request-target and Host header it actually received,
 * so each test asserts exactly what the proxy forwarded rather than only that a request
 * arrived.
 */
public class ProxyHandlerTest {

    private static Server upstream;
    private static int upstreamPort;
    private static HttpClient client;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = new Server();
        upstream.setHandler((request) -> {
            request.setResponseHeader("content-type", "text/plain");
            request.writeString(request.requestTarget + "|" + request.getRequestHeaderValue("host"));
        });
        upstream.start(0);
        upstreamPort = upstream.getSocket().getLocalPort();

        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void stopUpstream() throws Exception {
        if (upstream != null) {
            upstream.stop();
        }
    }

    /**
     * Proxies {@code path} through a ProxyHandler configured with {@code base},
     * returning "requestTarget|hostHeader" as seen upstream.
     */
    private static String proxied(String base, String path) throws Exception {
        Server proxy = new Server();
        proxy.setHandler(new ProxyHandler(URI.create("http://localhost:" + upstreamPort + base)));
        proxy.start(0);

        try {
            int port = proxy.getSocket().getLocalPort();

            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .timeout(Duration.ofSeconds(2))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, res.statusCode());
            return res.body();
        } finally {
            proxy.stop();
        }
    }

    private static String target(String base, String path) throws Exception {
        return proxied(base, path).split("\\|", 2)[0];
    }

    // -----------------------------------------------------------------------
    // Base path is preserved
    // -----------------------------------------------------------------------

    @Test
    void preservesBasePath() throws Exception {
        assertEquals("/my-server/api/ping", target("/my-server", "/api/ping"));
    }

    @Test
    void preservesBasePathWithTrailingSlash() throws Exception {
        assertEquals("/my-server/api/ping", target("/my-server/", "/api/ping"));
    }

    @Test
    void preservesNestedBasePath() throws Exception {
        assertEquals("/a/b/api/ping", target("/a/b", "/api/ping"));
    }

    @Test
    void preservesQueryString() throws Exception {
        assertEquals("/my-server/api/ping?a=1&b=2", target("/my-server", "/api/ping?a=1&b=2"));
    }

    @Test
    void preservesBasePathForRootRequest() throws Exception {
        assertEquals("/my-server/", target("/my-server", "/"));
    }

    // -----------------------------------------------------------------------
    // Targets without a path behave exactly as before
    // -----------------------------------------------------------------------

    @Test
    void emptyBasePathIsUnchanged() throws Exception {
        assertEquals("/api/ping", target("", "/api/ping"));
    }

    @Test
    void rootBasePathIsUnchanged() throws Exception {
        assertEquals("/api/ping", target("/", "/api/ping"));
    }

    // -----------------------------------------------------------------------
    // Host header still describes the upstream, not the base path
    // -----------------------------------------------------------------------

    @Test
    void sendsUpstreamHostHeader() throws Exception {
        String host = proxied("/my-server", "/api/ping").split("\\|", 2)[1];
        assertEquals("localhost:" + upstreamPort, host);
    }

    @Test
    void forwardsQueryStringsWithBracketsAndLeadingDoubleSlash() throws Exception {
        var proxy = new ProxyHandler(URI.create("http://localhost:" + upstreamPort));
        String brackets = TestConnection.run(proxy, "GET /api?ids[]=1&filter={a} HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(brackets.startsWith("HTTP/1.1 200 "), brackets);
        assertTrue(brackets.endsWith("\r\n\r\n/api?ids[]=1&filter={a}|localhost:" + upstreamPort), brackets);

        String doubleSlash = TestConnection.run(proxy, "GET //foo/bar HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertTrue(doubleSlash.startsWith("HTTP/1.1 200 "), doubleSlash);
        assertTrue(doubleSlash.endsWith("\r\n\r\n//foo/bar|localhost:" + upstreamPort), doubleSlash);
    }

    @Test
    void streamsSseEventsImmediatelyWithoutBuffering() throws Exception {
        var releaseSecondEvent = new java.util.concurrent.CountDownLatch(1);
        Server sseUpstream = new Server();
        sseUpstream.setHandler(req -> {
            req.setResponseHeader("content-type", "text/event-stream");
            try (var out = req.responseBody()) {
                out.write("data: first\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
                try {
                    releaseSecondEvent.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.write("data: second\n\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }
        });
        sseUpstream.start(0);

        Server sseProxy = new Server();
        sseProxy.setHandler(new ProxyHandler(URI.create("http://127.0.0.1:" + sseUpstream.getSocket().getLocalPort())));
        sseProxy.start(0);

        try (var socket = new java.net.Socket("127.0.0.1", sseProxy.getSocket().getLocalPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var in = new HttpInput(socket.getInputStream(), 8192);
            assertEquals("HTTP/1.1", in.readResponseVersion());
            assertEquals(200, in.readStatusCode());
            in.readReasonPhrase();
            while (in.readHeader() != null) {}

            // Must receive the first SSE event BEFORE upstream is allowed to finish
            var chunked = new ChunkedInputStream(in);
            byte[] buf = new byte[64];
            int n = chunked.read(buf);
            assertEquals("data: first\n\n", new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));

            releaseSecondEvent.countDown();
            String rest = new String(chunked.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals("data: second\n\n", rest);
        } finally {
            releaseSecondEvent.countDown();
            sseProxy.stop();
            sseUpstream.stop();
        }
    }
}
