package shadow.http.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

public class HttpExchange implements Exchange {

    public static final BiFunction<String, String, String> MERGE_HEADERS = (v1, v2) -> v1 + ", " + v2;

    // FIXME: do not go too high, this is already possibly almost 2mb of request header data
    // not sure anything valid will ever send that
    private static final int MAX_HEADERS = 200;

    final Connection connection;
    final HttpInput in;
    final OutputStream out;

    HttpRequest request;

    final long since;

    boolean upgraded = false;

    HttpExchange(Connection connection) throws IOException {
        this.since = System.currentTimeMillis();
        this.connection = connection;
        this.in = new HttpInput(connection.getInputStream(), connection.getServer().getConfig().getInputBufferSize());
        this.out = new BufferedOutputStream(connection.getOutputStream(), connection.getServer().getConfig().getOutputBufferSize());
    }

    @Override
    public void process() throws IOException {
        for (; ; ) {
            try {
                request = readRequest();
                // doing this in 2 steps so that the parsing parts of readRequest are easier to test
                request.prepare();
            } catch (BadRequestException e) {
                sendBadRequest(e);
                break;
            } catch (EOFException e) {
                break;
            }

            connection.getServer().handle(request);

            if (!request.isCommitted()) {
                request.setResponseStatus(404)
                        .setResponseHeader("content-type", "text/plain")
                        .writeString("Not found.");
            }

            if (request.requestHasBody()) {
                // FIXME: point is to entirely drain the request body
                // or should it be an error if the handler didn't do that?
                request.requestBody().close();
            }

            if (request.state != HttpRequest.State.COMPLETE) {
                throw new IllegalStateException("request not actually completed");
            }

            HttpRequest req = request;

            // FIXME: set some kind of flag on request that it is no longer valid?
            // but I want to avoid checking it all the time, so whats the point.
            // hope users to the correct thing

            request = null;

            if (upgraded || req.closeAfter) {
                break;
            }
        }
    }

    private void sendBadRequest(BadRequestException e) throws IOException {
        // just send raw text, maybe don't have a request object
        String body = e.getMessage();

        String response = "HTTP/1.1 400 \r\n" +
                "content-type: text/plain\r\n" +
                "content-length: " + body.length() + "\r\n" +
                "connection: close\r\n" +
                "\r\n" + body;

        out.write(response.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    HttpRequest readRequest() throws IOException {
        String method = in.readMethod();
        String target = in.readTarget();
        String version = in.readVersion();

        HttpRequest request = new HttpRequest(this, method, target, version);

        int headerCount = 0;

        while (true) {
            // Check for the empty line that terminates the header section
            Header header = in.readHeader();
            if (header == null) {
                break;
            }

            request.requestHeadersInOrder.add(header);
            request.requestHeaders.merge(header.name, header.value, MERGE_HEADERS);
            headerCount++;

            if (headerCount > MAX_HEADERS) {
                throw new BadRequestException(String.format("Client sent more than %d headers", MAX_HEADERS));
            }
        }
        return request;
    }
}
