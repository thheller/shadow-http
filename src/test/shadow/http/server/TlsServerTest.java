package shadow.http.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TlsServerTest {
    @Test
    void servesHttpsUsingConfiguredKeystore(@TempDir Path temp) throws Exception {
        Path store = temp.resolve("server.p12");
        String password = "test-only";
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Path log = temp.resolve("keytool.log");
        var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", password,
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                "-ext", "SAN=ip:127.0.0.1", "-validity", "1", "-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "keytool timed out");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }

        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(KeyStore.getInstance(store.toFile(), password.toCharArray()));
        var clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trust.getTrustManagers(), null);
        var server = new Server();
        server.setHandler(r -> r.writeString(r.isSecure() ? "secure" : "plain"));
        server.startSSL(Server.sslContextForFile(store.toString(), password), "127.0.0.1", 0);
        try (var client = HttpClient.newBuilder().sslContext(clientContext).build()) {
            var uri = URI.create("https://127.0.0.1:" + server.getSocket().getLocalPort() + "/");
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("secure", response.body());
        } finally {
            server.stop();
            server.join();
        }
    }
}
