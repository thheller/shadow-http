package shadow.http.server;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that start an actual Server and issue real HTTP requests
 * via {@link java.net.http.HttpClient}.
 */
public class IntegrationTest {

    private static Server server;
    private static HttpClient client;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        Path testFilesRoot = Path.of("src/test-env").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(testFilesRoot), "test-files directory must exist");

        HttpHandler fileHandler = FileHandler.forPath(testFilesRoot);

        HttpHandler customHandler = (request) -> {
            String target = request.requestTarget;

            // strip query string for routing
            int q = target.indexOf('?');
            String path = q == -1 ? target : target.substring(0, q);

            switch (path) {
                case "/hello" -> {
                    request.setResponseHeader("content-type", "text/plain");
                    request.writeString("hello world");
                }
                case "/echo" -> {
                    if (request.requestHasBody()) {
                        try (InputStream body = request.requestBody()) {
                            byte[] bytes = body.readAllBytes();
                            request.setResponseHeader("content-type", request.getRequestHeaderValue("content-type"));
                            request.writeString(new String(bytes, StandardCharsets.UTF_8));
                        }
                    } else {
                        request.setResponseStatus(400);
                        request.writeString("no body");
                    }
                }
                case "/status" -> {
                    String code = target.contains("?code=") ? target.substring(target.indexOf("?code=") + 6) : "200";
                    request.setResponseStatus(Integer.parseInt(code));
                    request.writeString("status " + code);
                }
                case "/headers" -> {
                    // echo back request headers as response body
                    StringBuilder sb = new StringBuilder();
                    for (Header h : request.getRequestHeadersInOrder()) {
                        sb.append(h.nameIn).append(": ").append(h.value).append("\n");
                    }
                    request.setResponseHeader("content-type", "text/plain");
                    request.writeString(sb.toString());
                }
                case "/large" -> {
                    // generate a response large enough to trigger compression
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 200; i++) {
                        sb.append("line ").append(i).append(": some repeated filler text for compression testing\n");
                    }
                    request.setResponseHeader("content-type", "text/plain");
                    request.writeString(sb.toString());
                }
                case "/chunked" -> {
                    request.setResponseHeader("content-type", "text/plain");
                    request.writeString("chunk1", false);
                    request.writeString("chunk2", false);
                    request.writeString("chunk3", true);
                }
                case "/ws" -> {
                    request.upgradeToWebSocket(new WebSocketHandler.Base() {
                        @Override
                        public void onText(String payload) throws IOException {
                            context.sendText("echo: " + payload);
                        }

                        @Override
                        public void onClose(int statusCode, String reason) {
                        }
                    });
                }
                case "/ws-close" -> {
                    // immediately close after upgrade
                    request.upgradeToWebSocket(new WebSocketHandler.Base() {
                        @Override
                        public WebSocketHandler start(WebSocketConnection ctx) {
                            super.start(ctx);
                            try {
                                ctx.sendText("goodbye");
                                ctx.sendClose(1000);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return this;
                        }
                    });
                }
                case "/ws-ping" -> {
                    request.upgradeToWebSocket(new WebSocketHandler.Base() {
                        @Override
                        public WebSocketHandler start(WebSocketConnection ctx) {
                            super.start(ctx);
                            try {
                                ctx.sendPing("hello".getBytes(StandardCharsets.UTF_8));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            return this;
                        }

                        @Override
                        public void onText(String payload) throws IOException {
                            context.sendText(payload);
                        }
                    });
                }
            }
        };

        server = new Server();
        server.setHandler(HandlerList.create(fileHandler, customHandler));
        server.start(0);

        port = server.getSocket().getLocalPort();

        client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    }

    @AfterAll
    static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofMillis(500));
    }

    // -----------------------------------------------------------------------
    // File serving tests
    // -----------------------------------------------------------------------

    @Test
    void servesIndexHtml() throws Exception {
        var response = client.send(
                request("/index.html").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<h1>Hello World</h1>"));
        assertEquals("text/html", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void servesDirectoryIndex() throws Exception {
        var response = client.send(
                request("/").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<h1>Hello World</h1>"));
    }

    @Test
    void servesTextFile() throws Exception {
        var response = client.send(
                request("/hello.txt").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello from test file!", response.body());
        assertEquals("text/plain", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void servesJsonFile() throws Exception {
        var response = client.send(
                request("/data.json").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"key\": \"value\""));
        assertEquals("application/json", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void servesCssFile() throws Exception {
        var response = client.send(
                request("/style.css").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("body { margin: 0; padding: 0; }", response.body());
        assertEquals("text/css", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void servesNestedFile() throws Exception {
        var response = client.send(
                request("/subdir/nested.txt").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Nested file content", response.body());
    }

    @Test
    void returns404ForMissingFile() throws Exception {
        var response = client.send(
                request("/nonexistent.txt").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void preventsPathTraversal() throws Exception {
        var response = client.send(
                request("/../dev/repl.clj").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void fileQueryStringStripped() throws Exception {
        var response = client.send(
                request("/hello.txt?v=123").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello from test file!", response.body());
    }

    @Test
    void conditionalGetReturns304() throws Exception {
        // first request to get Last-Modified
        var response1 = client.send(
                request("/hello.txt").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response1.statusCode());
        String lastModified = response1.headers().firstValue("last-modified").orElse(null);
        assertNotNull(lastModified, "should have last-modified header");

        // second request with If-Modified-Since
        var response2 = client.send(
                request("/hello.txt")
                        .header("if-modified-since", lastModified)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(304, response2.statusCode());
    }

    // -----------------------------------------------------------------------
    // Custom handler tests
    // -----------------------------------------------------------------------

    @Test
    void customHandlerPlainText() throws Exception {
        var response = client.send(
                request("/hello").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("hello world", response.body());
        assertEquals("text/plain", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void echoPostBody() throws Exception {
        String body = "testing echo endpoint";
        var response = client.send(
                request("/echo")
                        .header("content-type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(body, response.body());
    }

    @Test
    void echoPostJsonBody() throws Exception {
        String json = "{\"hello\": \"world\"}";
        var response = client.send(
                request("/echo")
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(json, response.body());
        assertEquals("application/json", response.headers().firstValue("content-type").orElse(""));
    }

    @Test
    void echoPostChunkedBody() throws Exception {
        String body = "hello chunked world";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        // Custom BodyPublisher that returns contentLength -1 to force chunked transfer-encoding
        HttpRequest.BodyPublisher chunkedPublisher = new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return -1; // unknown length -> HttpClient uses chunked encoding
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private boolean done = false;

                    @Override
                    public void request(long n) {
                        if (!done) {
                            done = true;
                            subscriber.onNext(ByteBuffer.wrap(bodyBytes));
                            subscriber.onComplete();
                        }
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }
        };

        var response = client.send(
                request("/echo")
                        .header("content-type", "text/plain")
                        .POST(chunkedPublisher)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(body, response.body());
    }

    @Test
    void echoPostChunkedBodyMultipleChunks() throws Exception {
        String part1 = "chunk-one-";
        String part2 = "chunk-two-";
        String part3 = "chunk-three";

        // Custom BodyPublisher that sends data in multiple chunks
        HttpRequest.BodyPublisher chunkedPublisher = new HttpRequest.BodyPublisher() {
            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    private int sent = 0;

                    @Override
                    public void request(long n) {
                        while (n-- > 0 && sent < 3) {
                            switch (sent++) {
                                case 0 -> subscriber.onNext(ByteBuffer.wrap(part1.getBytes(StandardCharsets.UTF_8)));
                                case 1 -> subscriber.onNext(ByteBuffer.wrap(part2.getBytes(StandardCharsets.UTF_8)));
                                case 2 -> {
                                    subscriber.onNext(ByteBuffer.wrap(part3.getBytes(StandardCharsets.UTF_8)));
                                    subscriber.onComplete();
                                }
                            }
                        }
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }
        };

        var response = client.send(
                request("/echo")
                        .header("content-type", "text/plain")
                        .POST(chunkedPublisher)
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals(part1 + part2 + part3, response.body());
    }

    @Test
    void echoWithNoBodyReturns400() throws Exception {
        var response = client.send(
                request("/echo").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
        assertEquals("no body", response.body());
    }

    @Test
    void customStatusCode() throws Exception {
        var response = client.send(
                request("/status?code=201").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, response.statusCode());
        assertEquals("status 201", response.body());
    }

    @Test
    void customStatusCode404() throws Exception {
        var response = client.send(
                request("/status?code=404").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, response.statusCode());
    }

    @Test
    void requestHeadersEchoed() throws Exception {
        var response = client.send(
                request("/headers")
                        .header("x-custom-header", "custom-value")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("x-custom-header: custom-value"),
                "response should contain custom header, got: " + response.body());
    }

    @Test
    void largeResponseCompressed() throws Exception {
        var response = client.send(
                request("/large")
                        .header("accept-encoding", "gzip")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, response.statusCode());

        // httpclient doesn't handle compression automatically, need to do manually
        byte[] body = new GZIPInputStream(response.body()).readAllBytes();
        String bodyString = new String(body);

        assertTrue(bodyString.contains("line 0:"));
        assertTrue(bodyString.contains("line 199:"));
    }

    @Test
    void chunkedResponse() throws Exception {
        var response = client.send(
                request("/chunked").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("chunk1chunk2chunk3", response.body());
    }

    // -----------------------------------------------------------------------
    // Multiple requests on same server (keep-alive)
    // -----------------------------------------------------------------------

    @Test
    void multipleSequentialRequests() throws Exception {
        for (int i = 0; i < 5; i++) {
            var response = client.send(
                    request("/hello").GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("hello world", response.body());
        }
    }

    // -----------------------------------------------------------------------
    // HEAD request
    // -----------------------------------------------------------------------

    @Test
    void headRequestForFile() throws Exception {
        var response = client.send(
                request("/hello.txt")
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().isEmpty(), "HEAD response should have no body");
        assertEquals("text/plain", response.headers().firstValue("content-type").orElse(""));
    }

    // -----------------------------------------------------------------------
    // HandlerList ordering: file handler takes priority over custom handler
    // -----------------------------------------------------------------------

    @Test
    void fileHandlerTakesPriorityForMatchingPaths() throws Exception {
        // /hello.txt exists as a file, so FileHandler should serve it
        // even though customHandler could also match it
        var response = client.send(
                request("/hello.txt").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("Hello from test file!", response.body());
    }

    @Test
    void customHandlerServesWhenFileDoesNotExist() throws Exception {
        // /hello is not a file, so FileHandler passes, customHandler serves
        var response = client.send(
                request("/hello").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("hello world", response.body());
    }

    // -----------------------------------------------------------------------
    // WebSocket integration tests
    // -----------------------------------------------------------------------

    private WebSocket.Builder wsBuilder(String path) {
        return client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(2));
    }

    private URI wsUri(String path) {
        return URI.create("ws://localhost:" + port + path);
    }

    @Test
    void webSocketEchoText() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(1);

        WebSocket ws = wsBuilder("/ws").buildAsync(wsUri("/ws"), new WebSocket.Listener() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    received.add(sb.toString());
                    sb.setLength(0);
                    latch.countDown();
                }
                webSocket.request(1);
                return null;
            }
        }).join();

        ws.sendText("hello", true);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "timed out waiting for echo");
        assertEquals(1, received.size());
        assertEquals("echo: hello", received.get(0));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void webSocketMultipleMessages() throws Exception {
        int messageCount = 5;
        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(messageCount);

        WebSocket ws = wsBuilder("/ws").buildAsync(wsUri("/ws"), new WebSocket.Listener() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    received.add(sb.toString());
                    sb.setLength(0);
                    latch.countDown();
                }
                webSocket.request(1);
                return null;
            }
        }).join();

        for (int i = 0; i < messageCount; i++) {
            ws.sendText("msg" + i, true);
        }

        assertTrue(latch.await(3, TimeUnit.SECONDS), "timed out waiting for messages");
        assertEquals(messageCount, received.size());
        for (int i = 0; i < messageCount; i++) {
            assertEquals("echo: msg" + i, received.get(i));
        }

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void webSocketServerInitiatedClose() throws Exception {
        var receivedText = new CopyOnWriteArrayList<String>();
        var closeLatch = new CountDownLatch(1);
        var closeCode = new int[]{-1};

        wsBuilder("/ws-close").buildAsync(wsUri("/ws-close"), new WebSocket.Listener() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    receivedText.add(sb.toString());
                    sb.setLength(0);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                closeCode[0] = statusCode;
                closeLatch.countDown();
                return null;
            }
        }).join();

        assertTrue(closeLatch.await(2, TimeUnit.SECONDS), "timed out waiting for close");
        assertEquals(1000, closeCode[0]);
        assertEquals(1, receivedText.size());
        assertEquals("goodbye", receivedText.get(0));
    }

    @Test
    void webSocketPingPong() throws Exception {
        var pongReceived = new CountDownLatch(1);
        var textLatch = new CountDownLatch(1);

        WebSocket ws = wsBuilder("/ws-ping").buildAsync(wsUri("/ws-ping"), new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                pongReceived.countDown();
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    textLatch.countDown();
                }
                webSocket.request(1);
                return null;
            }
        }).join();

        // server sends a ping on connect; send a ping from client too
        ws.sendPing(ByteBuffer.wrap("clientping".getBytes(StandardCharsets.UTF_8)));
        assertTrue(pongReceived.await(2, TimeUnit.SECONDS), "timed out waiting for pong");

        // verify the connection still works after ping/pong
        ws.sendText("after-ping", true);
        assertTrue(textLatch.await(2, TimeUnit.SECONDS), "timed out waiting for echo after ping");

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void webSocketLargeMessage() throws Exception {
        // build a message larger than typical buffer sizes
        StringBuilder large = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            large.append("line ").append(i).append(": padding text for large message test\n");
        }
        String largeMsg = large.toString();

        var received = new CopyOnWriteArrayList<String>();
        var latch = new CountDownLatch(1);

        WebSocket ws = wsBuilder("/ws").buildAsync(wsUri("/ws"), new WebSocket.Listener() {
            final StringBuilder sb = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    received.add(sb.toString());
                    sb.setLength(0);
                    latch.countDown();
                }
                webSocket.request(1);
                return null;
            }
        }).join();

        ws.sendText(largeMsg, true);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "timed out waiting for large echo");
        assertEquals(1, received.size());
        assertEquals("echo: " + largeMsg, received.get(0));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}
