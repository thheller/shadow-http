package shadow.http.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class HttpResponse {

    public enum State {
        PENDING,
        BODY,
        COMPLETE
    }

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] COLON_SP = {':', ' '};
    private static final byte[] HTTP11_START = {'H', 'T', 'T', 'P', '/', '1', '.', '1', ' '};

    private final HttpRequest request;

    private OutputStream out;

    State state = State.PENDING;

    public int status = 200;
    public String statusText = null;
    public long contentLength = -1;
    public String contentType = "text/html";
    public String contentCharset = null;
    // FIXME: this needs to be way more flexible, so the user can provide brotli compression or so if desired
    // hardcoded gzip is good enough for now
    public boolean compress = false;
    public boolean body = true;
    public boolean chunked = true;
    public boolean closeAfter = false;
    public Map<String, String> headers = new HashMap<>();

    public HttpResponse(HttpRequest request, OutputStream out) {
        this.request = request;
        this.out = out;
    }

    public HttpResponse setStatus(int status) {
        this.status = status;
        return this;
    }

    public HttpResponse setStatusText(String statusText) {
        this.statusText = statusText;
        return this;
    }

    public HttpResponse setContentLength(long contentLength) {
        this.contentLength = contentLength;
        if (contentLength > 0) {
            this.chunked = false;
            this.body = true;
        } else if (contentLength == 0) {
            this.body = false;
        }
        return this;
    }

    public HttpResponse setContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    public HttpResponse setContentCharset(String contentCharset) {
        this.contentCharset = contentCharset;
        return this;
    }

    public HttpResponse setCompress(boolean compress) {
        this.compress = compress;
        return this;
    }

    public HttpResponse setBody(boolean body) {
        this.body = body;
        return this;
    }

    public HttpResponse setChunked(boolean chunked) {
        this.body = true;
        this.chunked = chunked;
        return this;
    }

    public HttpResponse setHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public HttpResponse setCloseAfter(boolean close) {
        this.closeAfter = close;
        return this;
    }

    public void skipBody() throws IOException {
        if (state == State.PENDING) {
            beginResponse();
        }
        out.flush();
        this.state = State.COMPLETE;
    }

    public void writeString(String s) throws IOException {
        writeString(s, true);
    }

    public void writeString(String s, boolean isFinal) throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            if (isFinal) {
                // don't compress small responses
                if (s.length() < 850 || !compress) {
                    setContentLength(s.length());
                    setCompress(false);
                }
            }
            setBody(true);
            beginResponse();
        }

        // FIXME: actually respect contentChartset
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();

        if (isFinal) {
            out.close();
        }
    }

    private void checkComplete() {
        if (state == State.COMPLETE) {
            throw new IllegalStateException("Response already completed.");
        }
    }

    public void noContent() throws IOException {
        if (state != State.PENDING) {
            throw new IllegalStateException("noContent called while already: " + state);
        }

        setBody(false);
        beginResponse();
    }

    public void writeStream(InputStream in) throws IOException {
        writeStream(in, true);
    }

    public void writeStream(InputStream in, boolean isFinal) throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            setBody(true);
            beginResponse();
        }

        in.transferTo(out);
        out.flush();

        if (isFinal) {
            out.close();
        }
    }

    public OutputStream body() throws IOException {
        checkComplete();

        if (state == State.PENDING) {
            setBody(true);
            beginResponse();
        }

        return out;
    }

    void beginResponse() throws IOException {
        writeStatusLine(status, statusText);

        if (body) {
            String contentTypeToSend = contentType;
            if (contentCharset != null) {
                contentTypeToSend += "; charset=" + contentCharset;
            }

            writeHeader("content-type", contentTypeToSend);
        }

        if (body && compress) {
            String acceptEncoding = request.getHeaderValue("accept-encoding");

            // FIXME: not sure if worth adding dependencies to get zstd or brotli
            // only gzip is fine for now
            if (acceptEncoding == null || !acceptEncoding.contains("gzip")) {
                compress = false;
            } else {
                writeHeader("content-encoding", "gzip");
            }
        }

        if (body && chunked) {
            writeHeader("transfer-encoding", "chunked");
        } else if (contentLength >= 0) {
            writeHeader("content-length", Long.toString(contentLength));
        }

        if (!closeAfter && "close".equals(request.getHeaderValue("connection"))) {
            closeAfter = true;
        }

        if (closeAfter) {
            writeHeader("connection", "close");
        } else {
            writeHeader("connection", "keep-alive");
        }

        for (var entry : headers.entrySet()) {
            writeHeader(entry.getKey(), entry.getValue());
        }

        writeHeaderEnd();

        if (!body) {
            out.flush();
            state = State.COMPLETE;
            return;
        }

        state = State.BODY;

        // handler code should not be in control of closing underlying out stream since we manage keep-alive handling
        // but closing is still useful to know if the response was actually properly finished
        out = new InterceptedCloseOutputStream(this, out);

        if (chunked) {
            out = new ChunkedOutputStream(out);
        }

        // FIXME: if fixed contentLength known could wrap stream to ensure user sends correct amount

        if (compress) {
            out = new GZIPOutputStream(out, 8192, true);
        }
    }

    void writeStatusLine(int statusCode, String reasonPhrase) throws IOException {
        // FIXME: what about http/1.0? what happens if we answer 1.1 to a 1.0 request? does anything actually use 1.0?
        out.write(HTTP11_START);

        // status-code = 3DIGIT
        out.write('0' + (statusCode / 100));
        out.write('0' + (statusCode / 10) % 10);
        out.write('0' + statusCode % 10);
        out.write(' ');

        if (reasonPhrase != null && !reasonPhrase.isEmpty()) {
            out.write(reasonPhrase.getBytes(StandardCharsets.US_ASCII));
        }

        out.write(CRLF);
    }

    void writeHeader(String name, String value) throws IOException {
        out.write(name.getBytes(StandardCharsets.US_ASCII));
        out.write(COLON_SP);
        out.write(value.getBytes(StandardCharsets.US_ASCII));
        out.write(CRLF);
    }

    void writeHeaderEnd() throws IOException {
        out.write(CRLF);
    }
}
