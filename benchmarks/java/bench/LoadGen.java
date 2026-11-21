package bench;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Zero-dependency, low-overhead load generator for HTTP/1.1, SSE, and WebSockets.
 * Runs in an isolated JVM process from the server under test.
 */
public final class LoadGen {

    private static final int PHASE_WARMUP = 0;
    private static final int PHASE_MEASURED = 1;
    private static final int PHASE_DONE = 2;

    private static final int SOCKET_TIMEOUT_MS = 10_000;
    private static final int IO_BUFFER_SIZE = 16_384;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private LoadGen() {}

    /**
     * Compact log-linear latency histogram (nanoseconds).
     * 256 linear sub-buckets per power-of-2 range -> <0.4% relative precision from 1 ns to ~68 s.
     * Zero allocations on record().
     */
    public static final class LatencyHistogram {
        private static final int SUB_BITS = 8;
        private static final int SUB_COUNT = 1 << SUB_BITS; // 256
        private static final int SUB_MASK = SUB_COUNT - 1;
        private static final int MAX_EXP = 36; // covers up to 2^36 ns (~68.7s)
        private static final int TOTAL_BUCKETS = (MAX_EXP + 1) * SUB_COUNT;

        private final long[] counts = new long[TOTAL_BUCKETS];
        private long totalCount = 0;
        private long minNs = Long.MAX_VALUE;
        private long maxNs = 0;

        public void record(long ns) {
            if (ns < 0) ns = 0;
            totalCount++;
            if (ns < minNs) minNs = ns;
            if (ns > maxNs) maxNs = ns;

            int idx = bucketIndex(ns);
            counts[idx]++;
        }

        private static int bucketIndex(long ns) {
            if (ns < SUB_COUNT) {
                return (int) ns;
            }
            int highestBit = 63 - Long.numberOfLeadingZeros(ns); // >= 8
            int exp = highestBit - SUB_BITS + 1; // >= 1
            if (exp > MAX_EXP) {
                return TOTAL_BUCKETS - 1;
            }
            int shift = exp - 1;
            int sub = (int) ((ns >>> shift) & SUB_MASK);
            return exp * SUB_COUNT + sub;
        }

        private static long bucketUpperNs(int idx) {
            int exp = idx >>> SUB_BITS;
            int sub = idx & SUB_MASK;
            if (exp == 0) {
                return sub;
            }
            int shift = exp - 1;
            long base = ((long) (SUB_COUNT + sub)) << shift;
            long step = 1L << shift;
            return base + step - 1;
        }

        public void merge(LatencyHistogram other) {
            if (other.totalCount == 0) return;
            totalCount += other.totalCount;
            if (other.minNs < minNs) minNs = other.minNs;
            if (other.maxNs > maxNs) maxNs = other.maxNs;
            for (int i = 0; i < TOTAL_BUCKETS; i++) {
                counts[i] += other.counts[i];
            }
        }

        public long getTotalCount() {
            return totalCount;
        }

        public long getMinNs() {
            return totalCount == 0 ? 0 : minNs;
        }

        public long getMaxNs() {
            return totalCount == 0 ? 0 : maxNs;
        }

        public long percentileNs(double p) {
            if (totalCount == 0) return 0;
            long target = (long) Math.ceil((p / 100.0) * totalCount);
            if (target < 1) target = 1;
            long acc = 0;
            for (int i = 0; i < TOTAL_BUCKETS; i++) {
                acc += counts[i];
                if (acc >= target) {
                    long val = bucketUpperNs(i);
                    return Math.min(val, maxNs);
                }
            }
            return maxNs;
        }
    }

    private static Thread startWorker(String name, boolean useVirtual, Runnable task) {
        if (useVirtual) {
            return Thread.ofVirtual().name(name).start(task);
        }
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static Socket connect(String host, int port) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setSoTimeout(SOCKET_TIMEOUT_MS);
        s.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MS);
        return s;
    }

    private static String readAsciiLine(InputStream in, byte[] lineBuf) throws IOException {
        int pos = 0;
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (pos == 0) return null;
                return new String(lineBuf, 0, pos, StandardCharsets.ISO_8859_1);
            }
            if (b == '\n') {
                if (pos > 0 && lineBuf[pos - 1] == '\r') {
                    pos--;
                }
                return new String(lineBuf, 0, pos, StandardCharsets.ISO_8859_1);
            }
            if (pos == lineBuf.length) {
                lineBuf = Arrays.copyOf(lineBuf, lineBuf.length * 2);
            }
            lineBuf[pos++] = (byte) b;
        }
    }

    private static void skipExact(InputStream in, long n, byte[] scratch) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, scratch.length);
            int read = in.read(scratch, 0, toRead);
            if (read < 0) {
                throw new EOFException("Unexpected EOF while reading body (" + remaining + " bytes remaining)");
            }
            remaining -= read;
        }
    }

    private static long drainChunked(InputStream in, byte[] lineBuf, byte[] scratch) throws IOException {
        long total = 0;
        while (true) {
            String sizeLine = readAsciiLine(in, lineBuf);
            if (sizeLine == null) {
                throw new EOFException("Unexpected EOF in chunked response");
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size = Long.parseLong(hex, 16);
            if (size == 0) {
                // drain optional trailers until blank line
                while (true) {
                    String trailer = readAsciiLine(in, lineBuf);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return total;
            }
            skipExact(in, size, scratch);
            total += size;
            // trailing CRLF after chunk data
            readAsciiLine(in, lineBuf);
        }
    }

    private static final class HttpResponseMeta {
        int status;
        long contentLength = -1;
        boolean chunked = false;
        boolean closeAfter = false;
    }

    private static HttpResponseMeta readHttpHeaders(InputStream in, byte[] lineBuf) throws IOException {
        String statusLine = readAsciiLine(in, lineBuf);
        if (statusLine == null) {
            return null;
        }
        int sp1 = statusLine.indexOf(' ');
        if (sp1 < 0 || sp1 + 4 > statusLine.length()) {
            throw new IOException("Malformed status line: " + statusLine);
        }
        HttpResponseMeta meta = new HttpResponseMeta();
        meta.status = Integer.parseInt(statusLine.substring(sp1 + 1, sp1 + 4));

        while (true) {
            String line = readAsciiLine(in, lineBuf);
            if (line == null || line.isEmpty()) {
                break;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("content-length")) {
                meta.contentLength = Long.parseLong(value);
            } else if (name.equalsIgnoreCase("transfer-encoding")) {
                if (value.toLowerCase().contains("chunked")) {
                    meta.chunked = true;
                }
            } else if (name.equalsIgnoreCase("connection")) {
                if (value.toLowerCase().contains("close")) {
                    meta.closeAfter = true;
                }
            }
        }
        return meta;
    }

    // -------------------------------------------------------------------------
    // 1. HTTP/1.1 Keep-Alive Benchmark
    // -------------------------------------------------------------------------

    public static Map<String, Object> runHttp(
            String host,
            int port,
            String method,
            String path,
            byte[] body,
            int connections,
            int warmupSec,
            int durationSec) throws Exception {

        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(':').append(port).append("\r\n");
        req.append("Connection: keep-alive\r\n");
        if (body != null && body.length > 0) {
            req.append("Content-Type: application/octet-stream\r\n");
            req.append("Content-Length: ").append(body.length).append("\r\n");
        }
        req.append("\r\n");

        byte[] headerBytes = req.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] reqBytes;
        if (body != null && body.length > 0) {
            reqBytes = new byte[headerBytes.length + body.length];
            System.arraycopy(headerBytes, 0, reqBytes, 0, headerBytes.length);
            System.arraycopy(body, 0, reqBytes, headerBytes.length, body.length);
        } else {
            reqBytes = headerBytes;
        }

        final AtomicInteger phase = new AtomicInteger(PHASE_WARMUP);
        final CountDownLatch ready = new CountDownLatch(connections);
        final LatencyHistogram[] hists = new LatencyHistogram[connections];
        final long[] reqCounts = new long[connections];
        final long[] errCounts = new long[connections];
        final long[] byteCounts = new long[connections];
        final Thread[] threads = new Thread[connections];
        final boolean useVirtual = connections > 256;

        for (int i = 0; i < connections; i++) {
            final int id = i;
            hists[id] = new LatencyHistogram();
            threads[id] = startWorker("http-bench-" + id, useVirtual, () -> {
                byte[] lineBuf = new byte[4096];
                byte[] scratch = new byte[8192];
                Socket socket = null;
                InputStream in = null;
                OutputStream out = null;
                boolean signaledReady = false;
                try {
                    while (phase.get() != PHASE_DONE) {
                        try {
                            if (socket == null || socket.isClosed()) {
                                socket = connect(host, port);
                                in = new BufferedInputStream(socket.getInputStream(), IO_BUFFER_SIZE);
                                out = socket.getOutputStream();
                            }
                            if (!signaledReady) {
                                signaledReady = true;
                                ready.countDown();
                            }

                            int p = phase.get();
                            if (p == PHASE_DONE) break;

                            long t0 = (p == PHASE_MEASURED) ? System.nanoTime() : 0L;
                            out.write(reqBytes);
                            out.flush();

                            HttpResponseMeta meta = readHttpHeaders(in, lineBuf);
                            if (meta == null) {
                                // peer closed idle keep-alive socket; reconnect
                                closeQuietly(socket);
                                socket = null;
                                if (phase.get() == PHASE_MEASURED) {
                                    errCounts[id]++;
                                }
                                continue;
                            }

                            long bodyRead = 0;
                            if (meta.chunked) {
                                bodyRead = drainChunked(in, lineBuf, scratch);
                            } else if (meta.contentLength > 0) {
                                skipExact(in, meta.contentLength, scratch);
                                bodyRead = meta.contentLength;
                            } else if (meta.contentLength == -1 && meta.closeAfter) {
                                int n;
                                while ((n = in.read(scratch)) != -1) {
                                    bodyRead += n;
                                }
                            }

                            int pAfter = phase.get();
                            if (p == PHASE_MEASURED && pAfter != PHASE_DONE) {
                                long dt = System.nanoTime() - t0;
                                if (meta.status == 200) {
                                    reqCounts[id]++;
                                    byteCounts[id] += bodyRead;
                                    hists[id].record(dt);
                                } else {
                                    errCounts[id]++;
                                }
                            }

                            if (meta.closeAfter) {
                                closeQuietly(socket);
                                socket = null;
                            }
                        } catch (IOException e) {
                            closeQuietly(socket);
                            socket = null;
                            if (!signaledReady) {
                                signaledReady = true;
                                ready.countDown();
                            }
                            if (phase.get() == PHASE_MEASURED) {
                                errCounts[id]++;
                            }
                        }
                    }
                } finally {
                    if (!signaledReady) ready.countDown();
                    closeQuietly(socket);
                }
            });
        }

        ready.await(15, TimeUnit.SECONDS);
        if (warmupSec > 0) {
            Thread.sleep(warmupSec * 1000L);
        }

        long startNs = System.nanoTime();
        phase.set(PHASE_MEASURED);
        Thread.sleep(durationSec * 1000L);
        phase.set(PHASE_DONE);
        long elapsedNs = System.nanoTime() - startNs;

        for (Thread t : threads) {
            t.join(5000);
        }

        LatencyHistogram merged = new LatencyHistogram();
        long totalReqs = 0, totalErrs = 0, totalBytes = 0;
        for (int i = 0; i < connections; i++) {
            merged.merge(hists[i]);
            totalReqs += reqCounts[i];
            totalErrs += errCounts[i];
            totalBytes += byteCounts[i];
        }

        return buildResultMap("http", totalReqs, totalErrs, totalBytes, elapsedNs, merged, null);
    }

    // -------------------------------------------------------------------------
    // 2. SSE (Server-Sent Events) Benchmark
    // -------------------------------------------------------------------------

    /**
     * Streams decoded HTTP chunked bytes on the fly so SSE events are read as they arrive.
     */
    private static final class ChunkedReader extends InputStream {
        private final InputStream in;
        private final byte[] lineBuf;
        private long remainingInChunk = 0;
        private boolean eof = false;

        ChunkedReader(InputStream in) {
            this.in = in;
            this.lineBuf = new byte[256];
        }

        private void ensureChunk() throws IOException {
            if (eof || remainingInChunk > 0) return;
            String sizeLine = readAsciiLine(in, lineBuf);
            if (sizeLine == null) {
                eof = true;
                return;
            }
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size = Long.parseLong(hex, 16);
            if (size == 0) {
                while (true) {
                    String trailer = readAsciiLine(in, lineBuf);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                eof = true;
                return;
            }
            remainingInChunk = size;
        }

        @Override
        public int read() throws IOException {
            ensureChunk();
            if (eof) return -1;
            int b = in.read();
            if (b < 0) {
                eof = true;
                return -1;
            }
            remainingInChunk--;
            if (remainingInChunk == 0) {
                // consume CRLF after chunk payload
                readAsciiLine(in, lineBuf);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            ensureChunk();
            if (eof) return -1;
            int toRead = (int) Math.min(len, remainingInChunk);
            int n = in.read(buf, off, toRead);
            if (n < 0) {
                eof = true;
                return -1;
            }
            remainingInChunk -= n;
            if (remainingInChunk == 0) {
                readAsciiLine(in, lineBuf);
            }
            return n;
        }
    }

    public static Map<String, Object> runSse(
            String host,
            int port,
            String path,
            int expectedEvents,
            int connections,
            int warmupSec,
            int durationSec) throws Exception {

        String reqStr = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        byte[] reqBytes = reqStr.getBytes(StandardCharsets.ISO_8859_1);

        final AtomicInteger phase = new AtomicInteger(PHASE_WARMUP);
        final CountDownLatch ready = new CountDownLatch(connections);
        final LatencyHistogram[] ttfeHists = new LatencyHistogram[connections];
        final LatencyHistogram[] streamHists = new LatencyHistogram[connections];
        final long[] eventCounts = new long[connections];
        final long[] streamCounts = new long[connections];
        final long[] errCounts = new long[connections];
        final long[] byteCounts = new long[connections];
        final Thread[] threads = new Thread[connections];
        final boolean useVirtual = connections > 256;

        for (int i = 0; i < connections; i++) {
            final int id = i;
            ttfeHists[id] = new LatencyHistogram();
            streamHists[id] = new LatencyHistogram();
            threads[id] = startWorker("sse-bench-" + id, useVirtual, () -> {
                byte[] lineBuf = new byte[4096];
                boolean signaledReady = false;
                while (phase.get() != PHASE_DONE) {
                    Socket socket = null;
                    try {
                        int pStart = phase.get();
                        if (pStart == PHASE_DONE) break;

                        long t0 = (pStart == PHASE_MEASURED) ? System.nanoTime() : 0L;
                        socket = connect(host, port);
                        if (!signaledReady) {
                            signaledReady = true;
                            ready.countDown();
                        }

                        InputStream rawIn = new BufferedInputStream(socket.getInputStream(), IO_BUFFER_SIZE);
                        OutputStream out = socket.getOutputStream();
                        out.write(reqBytes);
                        out.flush();

                        HttpResponseMeta meta = readHttpHeaders(rawIn, lineBuf);
                        if (meta == null || meta.status != 200) {
                            if (phase.get() == PHASE_MEASURED) errCounts[id]++;
                            continue;
                        }

                        InputStream bodyIn = meta.chunked
                                ? new BufferedInputStream(new ChunkedReader(rawIn), 8192)
                                : rawIn;

                        int eventsInStream = 0;
                        boolean sawDataInEvent = false;
                        long streamBytes = 0;

                        while (true) {
                            String line = readAsciiLine(bodyIn, lineBuf);
                            if (line == null) {
                                break;
                            }
                            streamBytes += line.length() + 1;
                            if (line.isEmpty()) {
                                if (sawDataInEvent) {
                                    eventsInStream++;
                                    sawDataInEvent = false;
                                    int pNow = phase.get();
                                    if (pNow == PHASE_MEASURED) {
                                        eventCounts[id]++;
                                        if (eventsInStream == 1 && pStart == PHASE_MEASURED) {
                                            ttfeHists[id].record(System.nanoTime() - t0);
                                        }
                                    } else if (pNow == PHASE_DONE) {
                                        break;
                                    }
                                }
                            } else if (line.startsWith("data:")) {
                                sawDataInEvent = true;
                            }
                        }

                        int pEnd = phase.get();
                        if (pEnd != PHASE_DONE) {
                            if (eventsInStream == expectedEvents) {
                                if (pStart == PHASE_MEASURED) {
                                    streamCounts[id]++;
                                    byteCounts[id] += streamBytes;
                                    streamHists[id].record(System.nanoTime() - t0);
                                }
                            } else if (pEnd == PHASE_MEASURED) {
                                errCounts[id]++;
                            }
                        }
                    } catch (IOException e) {
                        if (!signaledReady) {
                            signaledReady = true;
                            ready.countDown();
                        }
                        if (phase.get() == PHASE_MEASURED) {
                            errCounts[id]++;
                        }
                    } finally {
                        closeQuietly(socket);
                    }
                }
                if (!signaledReady) ready.countDown();
            });
        }

        ready.await(15, TimeUnit.SECONDS);
        if (warmupSec > 0) {
            Thread.sleep(warmupSec * 1000L);
        }

        long startNs = System.nanoTime();
        phase.set(PHASE_MEASURED);
        Thread.sleep(durationSec * 1000L);
        phase.set(PHASE_DONE);
        long elapsedNs = System.nanoTime() - startNs;

        for (Thread t : threads) {
            t.join(5000);
        }

        LatencyHistogram mergedTtfe = new LatencyHistogram();
        LatencyHistogram mergedStream = new LatencyHistogram();
        long totalEvents = 0, totalStreams = 0, totalErrs = 0, totalBytes = 0;
        for (int i = 0; i < connections; i++) {
            mergedTtfe.merge(ttfeHists[i]);
            mergedStream.merge(streamHists[i]);
            totalEvents += eventCounts[i];
            totalStreams += streamCounts[i];
            totalErrs += errCounts[i];
            totalBytes += byteCounts[i];
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("streams", totalStreams);
        extra.put("streamsPerSec", totalStreams / (elapsedNs / 1e9));
        extra.put("streamP50Us", mergedStream.percentileNs(50.0) / 1000.0);
        extra.put("streamP95Us", mergedStream.percentileNs(95.0) / 1000.0);
        extra.put("streamP99Us", mergedStream.percentileNs(99.0) / 1000.0);

        // Primary latency reported is TTFE (time-to-first-event), primary throughput is events/sec
        return buildResultMap("sse", totalEvents, totalErrs, totalBytes, elapsedNs, mergedTtfe, extra);
    }

    // -------------------------------------------------------------------------
    // 3. WebSocket (RFC 6455) Echo Benchmark
    // -------------------------------------------------------------------------

    private static final class WsClient implements AutoCloseable {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final byte[] maskKey = new byte[4];
        final byte[] sendBuf;
        byte[] recvBuf = new byte[8192];

        WsClient(String host, int port, String path, int maxPayload) throws IOException {
            this.socket = connect(host, port);
            this.in = new BufferedInputStream(socket.getInputStream(), IO_BUFFER_SIZE);
            this.out = new BufferedOutputStream(socket.getOutputStream(), IO_BUFFER_SIZE);
            this.sendBuf = new byte[14 + maxPayload];

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String wsKey = Base64.getEncoder().encodeToString(nonce);

            // Fixed deterministic non-zero mask per connection (valid RFC 6455 masking)
            maskKey[0] = nonce[0];
            maskKey[1] = nonce[1];
            maskKey[2] = nonce[2];
            maskKey[3] = nonce[3];

            String upgradeReq = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";

            out.write(upgradeReq.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            byte[] lineBuf = new byte[2048];
            String statusLine = readAsciiLine(in, lineBuf);
            if (statusLine == null || !statusLine.contains(" 101 ")) {
                close();
                throw new IOException("WebSocket upgrade failed: " + statusLine);
            }

            String accept = null;
            while (true) {
                String line = readAsciiLine(in, lineBuf);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("sec-websocket-accept")) {
                    accept = line.substring(colon + 1).trim();
                }
            }

            String expectedAccept = computeAccept(wsKey);
            if (!expectedAccept.equals(accept)) {
                close();
                throw new IOException("Invalid Sec-WebSocket-Accept: expected=" + expectedAccept + " got=" + accept);
            }
        }

        private static String computeAccept(String key) throws IOException {
            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] digest = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
                return Base64.getEncoder().encodeToString(digest);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        void sendFrame(int opcode, byte[] payload, int len) throws IOException {
            int pos = 0;
            sendBuf[pos++] = (byte) (0x80 | (opcode & 0x0F));
            if (len <= 125) {
                sendBuf[pos++] = (byte) (0x80 | len);
            } else if (len <= 0xFFFF) {
                sendBuf[pos++] = (byte) (0x80 | 126);
                sendBuf[pos++] = (byte) ((len >>> 8) & 0xFF);
                sendBuf[pos++] = (byte) (len & 0xFF);
            } else {
                sendBuf[pos++] = (byte) (0x80 | 127);
                sendBuf[pos++] = 0;
                sendBuf[pos++] = 0;
                sendBuf[pos++] = 0;
                sendBuf[pos++] = 0;
                sendBuf[pos++] = (byte) ((len >>> 24) & 0xFF);
                sendBuf[pos++] = (byte) ((len >>> 16) & 0xFF);
                sendBuf[pos++] = (byte) ((len >>> 8) & 0xFF);
                sendBuf[pos++] = (byte) (len & 0xFF);
            }

            // Rotate mask slightly per frame
            maskKey[0] = (byte) (maskKey[0] + 1);
            sendBuf[pos++] = maskKey[0];
            sendBuf[pos++] = maskKey[1];
            sendBuf[pos++] = maskKey[2];
            sendBuf[pos++] = maskKey[3];

            for (int i = 0; i < len; i++) {
                sendBuf[pos++] = (byte) (payload[i] ^ maskKey[i & 3]);
            }

            out.write(sendBuf, 0, pos);
            out.flush();
        }

        private void readFully(byte[] dst, int off, int len) throws IOException {
            int read = 0;
            while (read < len) {
                int n = in.read(dst, off + read, len - read);
                if (n < 0) throw new EOFException("WebSocket EOF");
                read += n;
            }
        }

        /**
         * Reads one complete data message (assembling fragments if needed), returning payload length,
         * or -1 on close frame.
         */
        int readMessageLength() throws IOException {
            int assembled = 0;
            while (true) {
                int b0 = in.read();
                if (b0 < 0) throw new EOFException("WebSocket EOF");
                int b1 = in.read();
                if (b1 < 0) throw new EOFException("WebSocket EOF");

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                int lenByte = b1 & 0x7F;
                long payloadLen;
                if (lenByte <= 125) {
                    payloadLen = lenByte;
                } else if (lenByte == 126) {
                    int hi = in.read();
                    int lo = in.read();
                    if (hi < 0 || lo < 0) throw new EOFException("WebSocket EOF");
                    payloadLen = ((hi & 0xFF) << 8) | (lo & 0xFF);
                } else {
                    byte[] lenBytes = new byte[8];
                    readFully(lenBytes, 0, 8);
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (lenBytes[i] & 0xFFL);
                    }
                }

                byte[] serverMask = null;
                if (masked) {
                    serverMask = new byte[4];
                    readFully(serverMask, 0, 4);
                }

                int intLen = (int) payloadLen;
                if (intLen > recvBuf.length) {
                    recvBuf = new byte[Math.max(intLen, recvBuf.length * 2)];
                }
                if (intLen > 0) {
                    readFully(recvBuf, 0, intLen);
                    if (serverMask != null) {
                        for (int i = 0; i < intLen; i++) {
                            recvBuf[i] ^= serverMask[i & 3];
                        }
                    }
                }

                if (opcode == 0x8) { // CLOSE
                    return -1;
                } else if (opcode == 0x9) { // PING -> reply PONG
                    sendFrame(0xA, recvBuf, intLen);
                    continue;
                } else if (opcode == 0xA) { // PONG
                    continue;
                }

                assembled += intLen;
                if (fin) {
                    return assembled;
                }
            }
        }

        void sendClose() {
            try {
                byte[] closePayload = new byte[]{(byte) (1000 >>> 8), (byte) (1000 & 0xFF)};
                sendFrame(0x8, closePayload, 2);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    public static Map<String, Object> runWebSocket(
            String host,
            int port,
            String path,
            int payloadSize,
            int connections,
            int warmupSec,
            int durationSec) throws Exception {

        byte[] payload = new byte[payloadSize];
        for (int i = 0; i < payloadSize; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }

        final AtomicInteger phase = new AtomicInteger(PHASE_WARMUP);
        final CountDownLatch connected = new CountDownLatch(connections);
        final CountDownLatch go = new CountDownLatch(1);
        final LatencyHistogram[] hists = new LatencyHistogram[connections];
        final long[] msgCounts = new long[connections];
        final long[] errCounts = new long[connections];
        final long[] byteCounts = new long[connections];
        final Thread[] threads = new Thread[connections];
        final boolean useVirtual = connections > 256;

        for (int i = 0; i < connections; i++) {
            final int id = i;
            hists[id] = new LatencyHistogram();
            threads[id] = startWorker("ws-bench-" + id, useVirtual, () -> {
                WsClient client = null;
                boolean signaled = false;
                try {
                    client = new WsClient(host, port, path, Math.max(payloadSize, 128));
                    signaled = true;
                    connected.countDown();
                    go.await();

                    while (phase.get() != PHASE_DONE) {
                        int p = phase.get();
                        if (p == PHASE_DONE) break;

                        long t0 = (p == PHASE_MEASURED) ? System.nanoTime() : 0L;
                        client.sendFrame(0x1, payload, payloadSize);
                        int received = client.readMessageLength();
                        if (received < 0) {
                            // Server closed connection
                            if (phase.get() == PHASE_MEASURED) errCounts[id]++;
                            break;
                        }

                        int pAfter = phase.get();
                        if (p == PHASE_MEASURED && pAfter != PHASE_DONE) {
                            long dt = System.nanoTime() - t0;
                            if (received == payloadSize) {
                                msgCounts[id]++;
                                byteCounts[id] += received;
                                hists[id].record(dt);
                            } else {
                                errCounts[id]++;
                            }
                        }
                    }
                    client.sendClose();
                } catch (Exception e) {
                    if (!signaled) connected.countDown();
                    if (phase.get() == PHASE_MEASURED) {
                        errCounts[id]++;
                    }
                } finally {
                    if (client != null) client.close();
                }
            });
        }

        if (!connected.await(15, TimeUnit.SECONDS)) {
            phase.set(PHASE_DONE);
            go.countDown();
            throw new IOException("Timed out waiting for " + connections + " WebSocket connections");
        }
        go.countDown();

        if (warmupSec > 0) {
            Thread.sleep(warmupSec * 1000L);
        }

        long startNs = System.nanoTime();
        phase.set(PHASE_MEASURED);
        Thread.sleep(durationSec * 1000L);
        phase.set(PHASE_DONE);
        long elapsedNs = System.nanoTime() - startNs;

        for (Thread t : threads) {
            t.join(5000);
        }

        LatencyHistogram merged = new LatencyHistogram();
        long totalMsgs = 0, totalErrs = 0, totalBytes = 0;
        for (int i = 0; i < connections; i++) {
            merged.merge(hists[i]);
            totalMsgs += msgCounts[i];
            totalErrs += errCounts[i];
            totalBytes += byteCounts[i];
        }

        return buildResultMap("ws", totalMsgs, totalErrs, totalBytes, elapsedNs, merged, null);
    }

    private static Map<String, Object> buildResultMap(
            String kind,
            long ops,
            long errors,
            long bytes,
            long elapsedNs,
            LatencyHistogram hist,
            Map<String, Object> extra) {
        double elapsedSec = elapsedNs / 1e9;
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("kind", kind);
        res.put("ops", ops);
        res.put("errors", errors);
        res.put("bytes", bytes);
        res.put("elapsedSec", elapsedSec);
        res.put("opsPerSec", elapsedSec > 0 ? ops / elapsedSec : 0.0);
        res.put("mbPerSec", elapsedSec > 0 ? (bytes / (1024.0 * 1024.0)) / elapsedSec : 0.0);
        res.put("minUs", hist.getMinNs() / 1000.0);
        res.put("p50Us", hist.percentileNs(50.0) / 1000.0);
        res.put("p95Us", hist.percentileNs(95.0) / 1000.0);
        res.put("p99Us", hist.percentileNs(99.0) / 1000.0);
        res.put("maxUs", hist.getMaxNs() / 1000.0);
        if (extra != null) {
            res.putAll(extra);
        }
        return res;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
