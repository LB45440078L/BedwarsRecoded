package dev.bedwars.spigot.report;

import com.sun.net.httpserver.HttpServer;
import dev.bedwars.core.reporting.ReportingPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reporter is the client half of the controller's shared-secret check. Without the
 * header the controller refuses every report with 401, so the pod never registers and
 * the fleet looks empty. Verified against a real HTTP server: the header is read off
 * the wire rather than asserted against a mock.
 */
class HttpPodReporterTest {

    private record Stub(HttpServer server,
                        AtomicReference<String> token,
                        AtomicReference<String> path,
                        CountDownLatch hit) implements AutoCloseable {
        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static Stub stubController() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        CountDownLatch hit = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Bedwars-Token"));
            path.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
            hit.countDown();
        });
        server.start();
        return new Stub(server, token, path, hit);
    }

    private static String baseUrl(Stub stub) {
        return "http://127.0.0.1:" + stub.server().getAddress().getPort();
    }

    @Test
    void presentsTheSharedSecretOnEveryReport() throws Exception {
        try (Stub stub = stubController()) {
            HttpPodReporter reporter = new HttpPodReporter(baseUrl(stub), "shared-secret-fixture",
                    new ReportingPolicy());

            reporter.reportCapacity("bedwars-game-1", 20, 25);

            assertThat(stub.hit().await(5, TimeUnit.SECONDS)).as("a report reached the controller").isTrue();
            assertThat(stub.path().get()).isEqualTo("/pods/capacity");
            assertThat(stub.token().get()).isEqualTo("shared-secret-fixture");
            assertThat(reporter.authenticated()).isTrue();
        }
    }

    @Test
    void sendsNoHeaderAtAllWhenNoTokenIsConfigured() throws Exception {
        try (Stub stub = stubController()) {
            HttpPodReporter reporter = new HttpPodReporter(baseUrl(stub), new ReportingPolicy());

            reporter.reportCapacity("bedwars-game-1", 20, 25);

            assertThat(stub.hit().await(5, TimeUnit.SECONDS)).isTrue();
            // Absent rather than empty: a presented empty credential is rejected too.
            assertThat(stub.token().get()).isNull();
            assertThat(reporter.authenticated()).isFalse();
        }
    }
}
