package shadow.http.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class HttpExchange implements Exchange {
    final Connection connection;
    final InputStream in;
    final OutputStream out;
    final HttpInput httpIn;

    HttpRequest request;

    final long since;

    boolean upgraded = false;

    public HttpExchange(Connection connection) throws IOException {
        this.since = System.currentTimeMillis();
        this.connection = connection;
        this.in = connection.getInputStream();
        this.out = connection.getOutputStream();
        this.httpIn = new HttpInput(this, in);
    }

    @Override
    public void process() throws IOException {
        for (; ; ) {

            try {
                request = httpIn.readRequest();
            } catch (BadRequestException e) {
                // respond().setStatus(400).setContentType("text/plain").setCloseAfter(true).writeString(e.getMessage());
                // just send raw text, don't have a request object
                String body = e.getMessage();

                String response = "HTTP/1.1 400 \r\n" +
                        "content-type: text/plain\r\n" +
                        "content-length: " + body.length() + "\r\n" +
                        "connection: close\r\n" +
                        "\r\n" + body;

                out.write(response.getBytes(StandardCharsets.US_ASCII));
                break;
            } catch (EOFException e) {
                break;
            }

            connection.getServer().handle(request);

            if (!request.didRespond()) {
                request.respond().setStatus(404)
                        .setContentType("text/plain")
                        .writeString("Not found.");
            }

            if (request.hasBody()) {
                // FIXME: point is to entirely drain the request body
                // or should it be an error if the handler didn't do that?
                request.body().close();
            }

            if (request.response.state != HttpResponse.State.COMPLETE) {
                throw new IllegalStateException("request not actually completed");
            }

            HttpRequest req = request;

            // FIXME: set some kind of flag on request that it is no longer valid?
            // but I want to avoid checking it all the time, so whats the point.
            // hope users to the correct thing

            request = null;

            if (upgraded || req.response.closeAfter) {
                break;
            }
        }
    }
}
