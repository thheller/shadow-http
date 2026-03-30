package shadow.http.server;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class HttpInputBenchmark {

    private byte[] simpleGet;
    private byte[] typicalRequest;
    private byte[] manyHeaders;
    private byte[] chunkedBody;

    @Setup
    public void setup() {
        simpleGet = toBytes(
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n");

        typicalRequest = toBytes(
                "GET /index.html?q=search&page=1 HTTP/1.1\r\n" +
                "Host: www.example.com\r\n" +
                "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)\r\n" +
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n" +
                "Accept-Language: en-US,en;q=0.5\r\n" +
                "Accept-Encoding: gzip, deflate, br\r\n" +
                "Connection: keep-alive\r\n" +
                "Cookie: session=abc123; theme=dark\r\n" +
                "Cache-Control: no-cache\r\n" +
                "\r\n");

        StringBuilder sb = new StringBuilder();
        sb.append("POST /api/data HTTP/1.1\r\n");
        sb.append("Host: api.example.com\r\n");
        for (int i = 0; i < 50; i++) {
            sb.append("X-Custom-Header-").append(i).append(": value-").append(i).append("\r\n");
        }
        sb.append("\r\n");
        manyHeaders = toBytes(sb.toString());

        chunkedBody = toBytes(
                "1a\r\n" +
                "abcdefghijklmnopqrstuvwxyz\r\n" +
                "10\r\n" +
                "1234567890123456\r\n" +
                "0\r\n" +
                "\r\n");
    }

    @Benchmark
    public void requestLine_simple(Blackhole bh) throws IOException {
        HttpInput in = newInput(simpleGet);
        bh.consume(in.readMethod());
        bh.consume(in.readTarget());
        bh.consume(in.readVersion());
    }

    @Benchmark
    public void requestLine_typical(Blackhole bh) throws IOException {
        HttpInput in = newInput(typicalRequest);
        bh.consume(in.readMethod());
        bh.consume(in.readTarget());
        bh.consume(in.readVersion());
    }

    @Benchmark
    public void fullRequest_simple(Blackhole bh) throws IOException {
        HttpInput in = newInput(simpleGet);
        bh.consume(in.readMethod());
        bh.consume(in.readTarget());
        bh.consume(in.readVersion());
        Header h;
        while ((h = in.readHeader()) != null) {
            bh.consume(h);
        }
    }

    @Benchmark
    public void fullRequest_typical(Blackhole bh) throws IOException {
        HttpInput in = newInput(typicalRequest);
        bh.consume(in.readMethod());
        bh.consume(in.readTarget());
        bh.consume(in.readVersion());
        Header h;
        while ((h = in.readHeader()) != null) {
            bh.consume(h);
        }
    }

    @Benchmark
    public void fullRequest_manyHeaders(Blackhole bh) throws IOException {
        HttpInput in = newInput(manyHeaders);
        bh.consume(in.readMethod());
        bh.consume(in.readTarget());
        bh.consume(in.readVersion());
        Header h;
        while ((h = in.readHeader()) != null) {
            bh.consume(h);
        }
    }

    @Benchmark
    public void chunked(Blackhole bh) throws IOException {
        HttpInput in = newInput(chunkedBody);
        Chunk chunk;
        do {
            chunk = in.readChunk(65536);
            bh.consume(chunk);
        } while (!chunk.isLast());
    }

    @Benchmark
    public void headerOnly_single(Blackhole bh) throws IOException {
        HttpInput in = newInput(toBytes("Content-Type: application/json\r\n\r\n"));
        bh.consume(in.readHeader());
    }

    private static HttpInput newInput(byte[] data) {
        return new HttpInput(new ByteArrayInputStream(data), 8192);
    }

    private static byte[] toBytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(HttpInputBenchmark.class.getSimpleName())
                .build();

        new Runner(opt).run();
    }
}
