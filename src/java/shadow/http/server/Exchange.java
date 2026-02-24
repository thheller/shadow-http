package shadow.http.server;

import java.io.IOException;

/**
 * Represents the flow of messages a connection might exchange at any given point
 *
 * Usually starts out as HTTP but may upgrade to Websockets later
 */
public interface Exchange {
    void process() throws IOException;
}
