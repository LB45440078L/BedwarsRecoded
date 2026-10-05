package dev.bedwars.spigot.report;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code deployment.mode: AUTO} depends on this probe. It must be fast, must never
 * throw, and must treat anything other than a 200 as "no controller here".
 */
class ControllerProbeTest {

    private static HttpServer server(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void reachableWhenHealthzAnswers200() throws Exception {
        HttpServer server = server(200);
        try {
            assertThat(ControllerProbe.isReachable(baseUrl(server), Duration.ofSeconds(2))).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notReachableWhenHealthzAnswersNonOk() throws Exception {
        HttpServer server = server(503);
        try {
            assertThat(ControllerProbe.isReachable(baseUrl(server), Duration.ofSeconds(2))).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void notReachableWhenNothingIsListening() {
        // Port 1 is reserved and never serving here.
        assertThat(ControllerProbe.isReachable("http://127.0.0.1:1", Duration.ofMillis(750))).isFalse();
    }

    @Test
    void blankOrNullUrlIsNeverReachable() {
        assertThat(ControllerProbe.isReachable(null, Duration.ofMillis(200))).isFalse();
        assertThat(ControllerProbe.isReachable("", Duration.ofMillis(200))).isFalse();
        assertThat(ControllerProbe.isReachable("   ", Duration.ofMillis(200))).isFalse();
    }

    @Test
    void trailingSlashIsTolerated() throws Exception {
        HttpServer server = server(200);
        try {
            assertThat(ControllerProbe.isReachable(baseUrl(server) + "/", Duration.ofSeconds(2))).isTrue();
        } finally {
            server.stop(0);
        }
    }
}