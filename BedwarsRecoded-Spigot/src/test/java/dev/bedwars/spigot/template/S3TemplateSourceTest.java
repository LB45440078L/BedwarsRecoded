package dev.bedwars.spigot.template;

import com.sun.net.httpserver.HttpServer;
import dev.bedwars.api.dto.TemplateDescriptor;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end S3 template fetch against an in-JVM S3-compatible endpoint (no Docker,
 * no cluster): a real HTTP GET, real SigV4 headers, real file staging and checksum.
 */
class S3TemplateSourceTest {

    private record Stub(HttpServer server, AtomicReference<String> authorization,
                        AtomicReference<String> requestedPath) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Stub stubS3(byte[] payload, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server.createContext("/", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        return new Stub(server, auth, path);
    }

    private static TemplateDescriptor glacier() {
        return new TemplateDescriptor("Glacier", "1.0.0", dev.bedwars.api.dto.TemplateSource.S3, Optional.empty());
    }

    @Test
    void fetchesSignsAndStagesTheTemplate() throws Exception {
        byte[] payload = "slime-world-bytes".getBytes(StandardCharsets.UTF_8);
        try (Stub stub = stubS3(payload, 200)) {
            URI endpoint = URI.create("http://127.0.0.1:" + stub.server().getAddress().getPort());
            Path staging = Files.createTempDirectory("s3-staging");

            Path archive = new S3TemplateSource(endpoint, "bedwars-templates", "us-east-1",
                    "minioadmin", "minioadmin")
                    .materialise(glacier(), staging)
                    .join();

            assertThat(Files.readAllBytes(archive)).isEqualTo(payload);
            assertThat(archive.getFileName().toString()).isEqualTo("Glacier-1.0.0.slime");
            // Path-style addressing, exactly the object layout the templates use.
            assertThat(stub.requestedPath().get()).isEqualTo("/bedwars-templates/templates/Glacier/1.0.0.slime");
            // A real SigV4 Authorization header, not the old placeholder header.
            assertThat(stub.authorization().get())
                    .startsWith("AWS4-HMAC-SHA256 Credential=minioadmin/")
                    .contains("/us-east-1/s3/aws4_request")
                    .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        }
    }

    @Test
    void anonymousAccessSendsNoAuthorizationHeader() throws Exception {
        byte[] payload = "public".getBytes(StandardCharsets.UTF_8);
        try (Stub stub = stubS3(payload, 200)) {
            URI endpoint = URI.create("http://127.0.0.1:" + stub.server().getAddress().getPort());
            Path staging = Files.createTempDirectory("s3-staging-anon");

            new S3TemplateSource(endpoint, "bedwars-templates", "us-east-1")
                    .materialise(glacier(), staging)
                    .join();

            assertThat(stub.authorization().get()).isNull();
        }
    }

    @Test
    void nonSuccessStatusFails() throws Exception {
        try (Stub stub = stubS3("nope".getBytes(StandardCharsets.UTF_8), 403)) {
            URI endpoint = URI.create("http://127.0.0.1:" + stub.server().getAddress().getPort());
            Path staging = Files.createTempDirectory("s3-staging-403");

            assertThatThrownBy(() -> new S3TemplateSource(endpoint, "bedwars-templates", "us-east-1",
                    "minioadmin", "minioadmin").materialise(glacier(), staging).join())
                    .hasMessageContaining("S3 fetch failed (403)");
        }
    }

    @Test
    void checksumMismatchIsRejected() throws Exception {
        byte[] payload = "tampered".getBytes(StandardCharsets.UTF_8);
        try (Stub stub = stubS3(payload, 200)) {
            URI endpoint = URI.create("http://127.0.0.1:" + stub.server().getAddress().getPort());
            Path staging = Files.createTempDirectory("s3-staging-sha");
            TemplateDescriptor withBadChecksum = new TemplateDescriptor("Glacier", "1.0.0",
                    dev.bedwars.api.dto.TemplateSource.S3, Optional.of("deadbeef"));

            assertThatThrownBy(() -> new S3TemplateSource(endpoint, "bedwars-templates", "us-east-1")
                    .materialise(withBadChecksum, staging).join())
                    .hasMessageContaining("Checksum mismatch");
        }
    }
}