package dev.bedwars.controller.http;

import com.google.gson.JsonObject;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.pod.ServerRegistry;
import dev.bedwars.controller.provision.NoopProvisioner;
import dev.bedwars.controller.provision.ProvisionerKind;
import dev.bedwars.controller.queue.QueueManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the real controller HTTP server on an ephemeral port and drives it over
 * HTTP: a pod reports READY, a lobby request is dispatched to it, and the
 * Prometheus endpoint exposes the metrics KEDA scales on. No Docker required.
 */
class WebhookServerTest {

    private WebhookServer server;
    private HttpClient http;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        ServerRegistry registry = new ServerRegistry();
        QueueManager queue = new QueueManager(registry::allocate, 500, 10_000);
        ControllerConfig config = config("", port);
        server = new WebhookServer(port, queue, registry, config, new NoopProvisioner(LoggerFactory.getLogger("test")),
                LoggerFactory.getLogger("test"));
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Controller config for tests: open (no token) unless one is passed. */
    static ControllerConfig config(String apiToken, int port) {
        ControllerConfig.Provisioning provisioning = new ControllerConfig.Provisioning(
                ProvisionerKind.NONE, 0, 10, 25, true, 10,
                "bedwars-game", "bedwars-recoded-game:latest", "1024m", "solo",
                "http://localhost:" + port, "");
        return new ControllerConfig("local", "bedwars-solo", port, 2, 500, 10_000,
                provisioning, new ControllerConfig.Security(apiToken));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthzIsOk() throws Exception {
        HttpResponse<String> response = get("/healthz");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
    }

    @Test
    void readyPodIsDispatchedToLobbyRequest() throws Exception {
        HttpResponse<String> ready = post("/pods/ready", "{\"podId\":\"pod-1\",\"arenaGroup\":\"solo\"}");
        assertThat(ready.statusCode()).isEqualTo(200);

        QueueRequest request = new QueueRequest(UUID.randomUUID(), "alice", 0,
                Optional.of("solo"), Optional.empty(), System.currentTimeMillis());
        HttpResponse<String> dispatch = post("/lobby/queue", JsonSupport.gson().toJson(request));

        assertThat(dispatch.statusCode()).isEqualTo(200);
        JsonObject body = JsonSupport.gson().fromJson(dispatch.body(), JsonObject.class);
        assertThat(body.get("podAddress").getAsString()).isEqualTo("pod-1");
    }

    @Test
    void queueRequestWithoutCapacityReturnsRetry() throws Exception {
        QueueRequest request = new QueueRequest(UUID.randomUUID(), "bob", 0,
                Optional.of("solo"), Optional.empty(), System.currentTimeMillis());
        HttpResponse<String> dispatch = post("/lobby/queue", JsonSupport.gson().toJson(request));
        JsonObject body = JsonSupport.gson().fromJson(dispatch.body(), JsonObject.class);
        assertThat(body.get("podAddress").isJsonNull()).isTrue();
        assertThat(body.get("retryAfterMillis").getAsLong()).isGreaterThan(0);
    }

    @Test
    void metricsExposeQueueDepthAndFreeSlots() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-9\",\"arenaGroup\":\"doubles\"}");
        HttpResponse<String> metrics = get("/metrics");

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("bedwars_queue_depth");
        assertThat(metrics.body()).contains("bedwars_free_slots{group=\"doubles\"} 25");
    }

    @Test
    void drainingServerIsRemovedFromThePool() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-x\",\"arenaGroup\":\"solo\"}");
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 25");

        post("/pods/draining", "{\"podId\":\"pod-x\",\"gameId\":\"g\",\"remainingPlayers\":0}");

        // The drained server is gone from the pool, so its group no longer appears.
        assertThat(get("/metrics").body()).doesNotContain("bedwars_free_slots{group=\"solo\"}");
    }

    @Test
    void aMatchEndingReleasesItsSlotWithoutDroppingTheServer() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-c\",\"arenaGroup\":\"solo\",\"capacity\":4}");
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 4");
        // Dispatch two matches: two slots consumed, server still present.
        for (int i = 0; i < 2; i++) {
            QueueRequest request = new QueueRequest(UUID.randomUUID(), "p" + i, 0,
                    Optional.of("solo"), Optional.empty(), System.currentTimeMillis());
            post("/lobby/queue", JsonSupport.gson().toJson(request));
        }
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 2");

        post("/pods/ended", "{\"podId\":\"pod-c\",\"gameId\":\"g\"}");
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 3");
    }

    @Test
    void capacityReportUpdatesFreeSlots() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-cap\",\"arenaGroup\":\"solo\",\"capacity\":10}");
        post("/pods/capacity", "{\"podId\":\"pod-cap\",\"freeSlots\":3}");
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 3");
    }

    @Test
    void arenaStatusReportsFreeSlotsAndQueueDepthPerGroup() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-status\",\"arenaGroup\":\"solo\"}");

        HttpResponse<String> status = get("/lobby/arena-status");

        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"solo\"");
        assertThat(status.body()).contains("\"freeSlots\":25");
        assertThat(status.body()).contains("\"queued\":0");
    }

    @Test
    void malformedQueueBodyReturnsAReal400NotAnEmptyReply() throws Exception {
        // Missing required 'player'/'username': the handler throws, and the client
        // must still receive a 400 rather than a dropped connection.
        HttpResponse<String> response = post("/lobby/queue", "{\"priority\":0}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("bad_request");
    }
}