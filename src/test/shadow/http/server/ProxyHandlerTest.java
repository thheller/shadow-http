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
}
