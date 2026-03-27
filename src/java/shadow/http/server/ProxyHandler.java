package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.Set;

public class ProxyHandler implements HttpHandler {

    // Hop-by-hop headers that must not be forwarded (lowercase)
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "host"
    );

    private final HttpClient client;
    private final URI target;

    public ProxyHandler(HttpClient client, URI target) {
        this.client = client;
        this.target = target;
    }

    @Override
    public void handle(HttpRequest request) throws IOException {
        // Build target URI by appending the request target to the configured base
        var uri = target.resolve(request.requestTarget);

        // Build the outgoing request
        var builder = java.net.http.HttpRequest.newBuilder(uri);

        // Forward request headers, skipping hop-by-hop
        for (Header h : request.requestHeadersInOrder) {
            if (!HOP_BY_HOP.contains(h.name)) {
                builder.header(h.nameIn, h.value);
            }
        }

        // Set method and body
        BodyPublisher bodyPublisher;
        if (request.requestHasBody()) {
            InputStream requestBodyStream = request.requestBody();
            bodyPublisher = BodyPublishers.ofInputStream(() -> requestBodyStream);
        } else {
            bodyPublisher = BodyPublishers.noBody();
        }
        builder.method(request.requestMethod, bodyPublisher);

        var outgoing = builder.build();

        try {
            var response = client.send(outgoing, BodyHandlers.ofInputStream());

            request.setResponseStatus(response.statusCode());

            // Forward response headers, skipping hop-by-hop
            response.headers().map().forEach((name, values) -> {
                String lowerName = name.toLowerCase();
                if (!HOP_BY_HOP.contains(lowerName)) {
                    // take last value for each header, matching the single-value model
                    request.setResponseHeader(lowerName, values.get(values.size() - 1));
                }
            });

            // Let the server handle chunking/compression itself
            request.autoCompress = false;

            // Stream the response body
            try (InputStream body = response.body()) {
                if (body != null) {
                    request.writeStream(body);
                } else {
                    request.skipBody();
                }
            }
        } catch (InterruptedException e) {
            throw new IOException("Proxy request interrupted", e);
        }
    }
}
