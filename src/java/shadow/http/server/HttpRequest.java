package shadow.http.server;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class HttpRequest {
    public final String method;
    public final String target;
    public final String httpVersion;
    public final List<Header> headersInOrder;
    public final Map<String,String> headers;

    public HttpRequest(String method, String requestTarget, String httpVersion) {
        this.method = method;
        this.target = requestTarget;
        this.httpVersion = httpVersion;
        this.headersInOrder = new LinkedList<Header>();
        this.headers = new HashMap<>();
    }

    public String getHeaderValue(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    // the assumption is that the client is a browser, and they usually do not send duplicate headers
    // but if something actually needs to check it can
    public boolean hasRepeatedHeaders() {
        return headers.size() != headersInOrder.size();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(target).append(' ').append(httpVersion).append("\r\n");
        for (Header h : headersInOrder) {
            sb.append(h.nameIn).append(": ").append(h.value).append("\r\n");
        }
        sb.append("\r\n");
        return sb.toString();
    }

    public String getMethod() {
        return method;
    }

    public String getTarget() {
        return target;
    }

    public String getHttpVersion() {
        return httpVersion;
    }

    public List<Header> getHeadersInOrder() {
        return headersInOrder;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }
}
