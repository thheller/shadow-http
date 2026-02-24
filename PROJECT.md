# shadow-http: Architecture & Flow

A lightweight, zero-dependency HTTP/1.1 server built on plain Java sockets and virtual threads.

---

## Overview

```
Server
  └── accepts TCP connections → SocketConnection (virtual thread)
        └── HttpExchange        (parses HTTP, dispatches to handlers)
              ├── HttpHandler[] (chain of handlers, first to respond wins)
              └── may upgrade → WebSocketExchange (takes over the same socket)
```

---

## Starting the Server

```java
Server server = new Server();
server.setHandlers(handler1, handler2);
server.start(5007);
```

`server.start()` opens a `ServerSocket` on port **5007** and loops forever, accepting connections. Each accepted `Socket` is handed to a `SocketConnection` and submitted to a **virtual-thread executor** (`Executors.newVirtualThreadPerTaskExecutor()`), so every connection gets its own lightweight thread.

---

## Connection & Exchange Lifecycle

### `SocketConnection` (one per TCP connection)

- Wraps the socket's streams in buffered I/O (8 KB input, 64 KB output).
- Starts with an `HttpExchange` as the active `Exchange`.
- Runs a `process()` loop:
  - Calls `exchange.process()`.
  - If the exchange replaces itself (e.g., upgrades to WebSocket), loops again with the new exchange.
  - If the same exchange is still active after `process()` returns, breaks out and closes the socket.

### `HttpExchange` (HTTP/1.1 keep-alive loop)

- Uses `HttpInput` to parse request after request on the same connection (keep-alive).
- For each request:
  1. Reads the full HTTP/1.1 request via `HttpInput.readRequest()` (RFC 9112).
  2. Calls `Server.handle(ctx, request)` — iterates the handler chain until one responds.
  3. If no handler responded, automatically sends **404 Not Found**.
  4. Resets state and loops for the next request — unless the connection was upgraded or the response had `Connection: close`.

---

## Request Parsing — `HttpInput`

`HttpInput` is a strict RFC 9112 stream parser:

- Skips leading CRLFs before the request line.
- Parses `method SP request-target SP HTTP-version CRLF`.
- Reads header fields into `HttpRequest` (both an ordered list and a lower-cased lookup map).
- Validates the `Host` header.
- Also supports chunked body parsing via `readChunk()`.

The result is an **`HttpRequest`** object:

| Field | Description |
|---|---|
| `method` | `"GET"`, `"POST"`, etc. |
| `target` | Request path, e.g. `"/index.html"` |
| `httpVersion` | `"HTTP/1.1"` |
| `headers` | Lower-cased name → value map |
| `headersInOrder` | Original ordered list of `Header` objects |

---

## Handler Chain

```java
server.setHandlers(filesHandler, appHandler);
```

Handlers are tried **in order**. The loop stops as soon as `ctx.didRespond()` returns `true`. This means earlier handlers act as gatekeepers or static-file servers, and later ones handle application logic.

### `HttpHandler` interface

```java
public interface HttpHandler {
    void handle(HttpContext context, HttpRequest request) throws IOException;
    default void addedToServer(Server server) {}  // lifecycle hook
    default void cleanup() {}                     // lifecycle hook
}
```

Returning from `handle()` without calling `ctx.respond()` passes control to the next handler.

---

## Building a Response — `HttpResponse`

`ctx.respond()` creates and registers a new `HttpResponse`. It exposes a **fluent builder** that must eventually write the body to finish the response.

```java
// Simple string response
ctx.respond()
   .setStatus(200)
   .setContentType("text/plain")
   .writeString("Hello!");

// Stream a file with known length
ctx.respond()
   .setStatus(200)
   .setContentType("text/html")
   .setContentLength(fileSize)
   .writeStream(inputStream);

// Get raw OutputStream for streaming (e.g. SSE)
HttpResponse response = ctx.respond()
    .setStatus(200)
    .setContentType("text/event-stream")
    .setChunked(true)
    .setBody(true)
    .setCompress(false);
OutputStream out = response.body();
```

### Response state machine

```
PENDING → (beginResponse() writes status + headers) → BODY → (close) → COMPLETE
```

`beginResponse()` is called automatically on the first write. It outputs the status line and all headers, then wraps `out` with the appropriate layers:

1. `InterceptedCloseOutputStream` — catches `close()` so the handler can't accidentally close the socket; marks the response `COMPLETE` instead.
2. `ChunkedOutputStream` — if `chunked = true` (the default when no `Content-Length` is set).
3. `GZIPOutputStream` — if `compress = true` and the client sent `Accept-Encoding: gzip`.

### Key setters

| Method | Effect |
|---|---|
| `setStatus(int)` | HTTP status code (default `200`) |
| `setContentType(String)` | `Content-Type` header |
| `setContentLength(long)` | Sets `Content-Length`, disables chunked encoding |
| `setChunked(boolean)` | Enables/disables `Transfer-Encoding: chunked` |
| `setCompress(boolean)` | Enables/disables gzip compression |
| `setHeader(String, String)` | Adds an arbitrary response header |
| `setCloseAfter(boolean)` | Sends `Connection: close` and closes after response |
| `noContent()` | Finishes with no body (e.g. `304 Not Modified`) |

---

## WebSocket Upgrade

### From the HTTP handler

```java
ctx.upgradeToWebSocket(new WebSocketHandler() {
    @Override
    public WebSocketHandler onText(WebSocketContext ctx, String payload) throws IOException {
        ctx.sendText(payload); // echo
        return this;
    }
});
```

`HttpExchange.upgradeToWebSocket()` performs the RFC 6455 handshake:

1. Validates `Upgrade: websocket`, `Connection: upgrade`, `Sec-WebSocket-Key`, and `Sec-WebSocket-Version: 13`.
2. Computes `Sec-WebSocket-Accept` (SHA-1 of key + magic GUID, base64-encoded).
3. Optionally negotiates `permessage-deflate` compression (RFC 7692).
4. Sends the `101 Switching Protocols` response.
5. Creates a `WebSocketExchange` and installs it as the new active `Exchange` on `SocketConnection`.

After returning from the handler, `SocketConnection` detects the exchange has changed and loops into `WebSocketExchange.process()`.

### `WebSocketExchange`

- Reads frames via `WebSocketInput`.
- Assembles **fragmented messages** transparently.
- Decompresses per-message deflate payloads when negotiated (RSV1 bit).
- Dispatches to `WebSocketHandler`:
  - `start(ctx)` — called once when the WebSocket session begins; return `this` or a different handler.
  - `onText(ctx, payload)` — called for each complete text message; return `this` or a new handler to swap state.
  - `onBinary(ctx, payload)` — same for binary messages.
  - `onClose(statusCode, reason)` — called when the connection closes.
- Handles ping/pong and close frames at the protocol level, invisible to the application handler.
- Outgoing sends are **mutex-locked** (`ReentrantLock`) so multiple threads can safely call `sendText()` concurrently.
- Large outgoing messages are automatically split into frames (max 1 MB per frame).
- Compression is skipped for messages smaller than 256 bytes.

### `WebSocketHandler` return convention

Every `onText` / `onBinary` / `start` callback returns a `WebSocketHandler`. Returning `this` keeps the same handler. Returning a different instance swaps in a new handler, enabling **stateful protocol state machines** without extra fields.

---

## File Serving — `FileHandler`

```java
HttpHandler files = FileHandler.forPath("docs").findFiles().watch();
```

- **`forPath(String)`** — resolves and normalises the root directory path.
- **`findFiles()`** — walks the directory tree and registers all readable, non-hidden files in a `ConcurrentHashMap<String, FileInfo>`.
- **`watch()`** — starts a background daemon thread using `WatchService` that watches for `CREATE`, `MODIFY`, and `DELETE` events, keeping the in-memory map up to date.

### Serving logic

1. Only handles `GET` requests; passes anything else to the next handler.
2. Strips query strings from the URI.
3. Falls back: `/foo` → `/foo/index.html`, `/foo/` → `/foo/index.html`.
4. Checks `If-Modified-Since` and replies with `304` if the file hasn't changed.
5. Guesses MIME type from file extension via `Config`.
6. Enables gzip compression for text types when the response body is ≥ 850 bytes.
7. Sets `Cache-Control: private, no-cache` — good for development: clients revalidate every time but don't re-download unchanged files.

---

## Server-Sent Events (SSE)

SSE works over a plain HTTP response using `chunked` encoding with `setBody(true)`:

```java
HttpResponse response = ctx.respond()
    .setStatus(200)
    .setContentType("text/event-stream")
    .setChunked(true)
    .setBody(true)
    .setCompress(false); // gzip flush doesn't work well for streaming

while (true) {
    response.writeString("data: " + System.currentTimeMillis() + "\n\n", false);
    // false = not the final write; keeps the stream open
    Thread.sleep(1000);
}
```

The `isFinal = false` flag on `writeString` flushes without closing the output stream, keeping the connection alive for subsequent events. Because the handler blocks the virtual thread, this doesn't exhaust OS threads.
