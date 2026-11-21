# shadow-http Benchmarks

Self-contained benchmark suite comparing `shadow-http` against `ring-jetty-adapter` (Jetty 12) and `http-kit` across basic HTTP requests, Server-Sent Events (SSE), and WebSockets.

## Servers Compared

| Name | Implementation | Notes |
|---|---|---|
| `shadow-http` | `shadow.http.server` + `ring-handler` | Apples-to-apples Ring adapter (virtual threads per connection, `ring.websocket` + `StreamableResponseBody`) |
| `shadow-http-native` | `shadow.http.server` direct `HttpHandler` / `WebSocketHandler` | Direct Java handler API without Ring map allocation overhead |
| `jetty` | `ring/ring-jetty-adapter` (1.15.6 / Jetty 12) | Exact same Ring handler as `shadow-http`, `:max-threads 200` |
| `http-kit` | `http-kit/http-kit` (2.8.1) | Same HTTP Ring handler + idiomatic `org.httpkit.server/as-channel` for SSE and WebSockets |

## Scenarios

1. **`http-plaintext`** — `GET /plaintext` over persistent HTTP/1.1 keep-alive connections (`"Hello, World!"`).
2. **`http-json`** — `GET /json` over persistent HTTP/1.1 keep-alive connections (~110 B JSON response).
3. **`http-post`** — `POST /echo` with a 1 KB request body echoed back over keep-alive connections.
4. **`sse-stream`** — `GET /sse?events=500` streaming 500 flushed `text/event-stream` events per request across concurrent streams. Reports `events/s` and Time-To-First-Event (TTFE) latency percentiles.
5. **`ws-echo`** — Persistent RFC 6455 WebSocket connections echoing 64-byte text frames in a tight round-trip loop (no `permessage-deflate` so all servers are compared on raw frame handling).
6. **`ws-echo-4k`** — Same as `ws-echo` with 4 KB text frames.

## Methodology

- **Process isolation**: Each server runs in its own fresh JVM (`-Xms512m -Xmx512m`), and the load generator runs in a separate JVM process so GC, JIT, and thread scheduling do not interfere across servers or between client and server.
- **Zero external tools required**: The load generator (`java/bench/LoadGen.java`) uses raw TCP sockets (`TCP_NODELAY`), chunked SSE decoding, RFC 6455 WebSocket framing, and a zero-allocation log-linear latency histogram.
- **Warmup + measured window**: Every scenario runs a warmup phase before measuring throughput and p50/p95/p99 latencies.

## Usage

Run the full comparison suite:

```bash
./benchmarks/run.sh
```

Quick smoke run (1s warmup, 2s measured):

```bash
./benchmarks/run.sh --warmup 1 --duration 2
```

Filter specific servers or tests, adjust concurrency, and save raw EDN output:

```bash
./benchmarks/run.sh \
  --servers shadow-http,jetty,http-kit \
  --tests http-plaintext,sse-stream,ws-echo \
  --connections 64 \
  --warmup 3 \
  --duration 10 \
  --out benchmarks/results.edn
```

### Running a Server Standalone (for `wrk`, `oha`, `k6`, etc.)

You can also start any server individually and point external tools at it:

```bash
cd benchmarks
clojure -M:server shadow-http --port 18080
# or: jetty, http-kit, shadow-http-native
```

Endpoints exposed: `GET /health`, `GET /plaintext`, `GET /json`, `POST /echo`, `GET /sse?events=500&interval_ms=0`, `GET /ws`.

## Results

Run command: `./benchmarks/run.sh --warmup 3 --duration 5`
Environment: `Linux 6.12.113+deb13-arm64`, `10 CPUs`, `OpenJDK 27`, `32 concurrent connections`, `500 SSE events/stream`, `-Xms512m -Xmx512m` per JVM, `0 errors` across all runs.

The numbers vary a little depending on the order these are run in. They also all ran a VM, not on an actual machine. Overall trends are accurate though. Ring overhead very minimal in these and very competitive with the "native" version.

### `http-plaintext` — HTTP `GET /plaintext` (keep-alive)

| Server | Throughput (req/s) | vs base | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 133,160 | 1.00x | 201.2 us | 530.4 us | 870.4 us | 0 |
| `shadow-http-native` | 151,215 | 1.14x | 183.8 us | 440.3 us | 716.8 us | 0 |
| `jetty` | 83,851 | 0.63x | 279.6 us | 999.4 us | 1.56 ms | 0 |
| `http-kit` | 115,138 | 0.86x | 252.4 us | 499.7 us | 671.7 us | 0 |

### `http-json` — HTTP `GET /json` (keep-alive)

| Server | Throughput (req/s) | vs base | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 151,584 | 1.00x | 183.3 us | 448.5 us | 741.4 us | 0 |
| `shadow-http-native` | 137,959 | 0.91x | 196.6 us | 507.9 us | 845.8 us | 0 |
| `jetty` | 83,261 | 0.55x | 283.6 us | 1.00 ms | 1.56 ms | 0 |
| `http-kit` | 115,240 | 0.76x | 252.9 us | 503.8 us | 684.0 us | 0 |

### `http-post` — HTTP `POST /echo` 1 KB (keep-alive)

| Server | Throughput (req/s) | vs base | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 153,161 | 1.00x | 177.2 us | 448.5 us | 829.4 us | 0 |
| `shadow-http-native` | 141,399 | 0.92x | 192.0 us | 503.8 us | 849.9 us | 0 |
| `jetty` | 80,410 | 0.53x | 290.8 us | 1.05 ms | 1.69 ms | 0 |
| `http-kit` | 110,488 | 0.72x | 259.6 us | 536.6 us | 759.8 us | 0 |

### `sse-stream` — SSE `GET /sse?events=500` (flushed event stream, latency = TTFE)

| Server | Throughput (events/s) | vs base | TTFE p50 | TTFE p95 | TTFE p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 1,387,804 | 1.00x | 7.26 ms | 11.34 ms | 13.96 ms | 0 |
| `shadow-http-native` | 1,274,135 | 0.92x | 7.90 ms | 12.03 ms | 14.75 ms | 0 |
| `jetty` | 1,352,197 | 0.97x | 7.24 ms | 16.61 ms | 22.09 ms | 0 |
| `http-kit` | 1,354,236 | 0.98x | 7.19 ms | 13.57 ms | 16.97 ms | 0 |

### `ws-echo` — WebSocket `/ws` echo (64 B text frames)

| Server | Throughput (msg/s) | vs base | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 162,317 | 1.00x | 166.9 us | 448.5 us | 768.0 us | 0 |
| `shadow-http-native` | 150,353 | 0.93x | 180.2 us | 494.6 us | 821.2 us | 0 |
| `jetty` | 93,278 | 0.57x | 255.0 us | 886.8 us | 1.39 ms | 0 |
| `http-kit` | 115,274 | 0.71x | 250.9 us | 515.1 us | 727.0 us | 0 |

### `ws-echo-4k` — WebSocket `/ws` echo (4 KB text frames)

| Server | Throughput (msg/s) | vs base | p50 | p95 | p99 | Errors |
|---|---:|---:|---:|---:|---:|---:|
| `shadow-http` | 152,973 | 1.00x | 167.4 us | 511.0 us | 938.0 us | 0 |
| `shadow-http-native` | 135,980 | 0.89x | 192.0 us | 567.3 us | 993.3 us | 0 |
| `jetty` | 83,253 | 0.54x | 283.6 us | 1.02 ms | 1.61 ms | 0 |
| `http-kit` | 99,141 | 0.65x | 286.7 us | 628.7 us | 884.7 us | 0 |
