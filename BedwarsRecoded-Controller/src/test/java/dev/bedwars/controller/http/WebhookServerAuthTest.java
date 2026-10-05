package dev.bedwars.controller.http;

import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.pod.ServerRegistry;
import dev.bedwars.controller.provision.NoopProvisioner;
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
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The remote controller API must not be usable without the shared secret once one is
 * configured: a game server on another machine (or a stray caller) must not be able
 * to report fake readiness or steal queue slots.
 */
class WebhookServerAuthTest {

    private static final String TOKEN = "s3cr3t-test-token";

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
        ControllerConfig config = WebhookServerTest.config(TOKEN, port);
        server = new WebhookServer(port, queue, registry, config,
                new NoopProvisioner(LoggerFactory.getLogger("test")), LoggerFactory.getLogger("test"));
        server.start();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private HttpResponse<String> post(String path, String json, String tokenHeader, String authHeader)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (tokenHeader != null) {
            builder.header("X-Bedwars-Token", tokenHeader);
        }
        if (authHeader != null) {
            builder.header("Authorization", authHeader);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mutatingRequestWithoutTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("/pods/ready", "{\"podId\":\"pod-1\",\"arenaGroup\":\"solo\"}", null, null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("unauthorized");
    }

    @Test
    void mutatingRequestWithWrongTokenIsRejected() throws Exception {
        HttpResponse<String> response = post("/pods/ready", "{\"podId\":\"pod-1\"}", "nope", null);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void mutatingRequestWithCorrectTokenIsAccepted() throws Exception {
        HttpResponse<String> response = post("/pods/ready", "{\"podId\":\"pod-1\",\"arenaGroup\":\"solo\"}", TOKEN, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("accepted");
    }

    @Test
    void bearerAuthorizationIsAlsoAccepted() throws Exception {
        HttpResponse<String> response = post("/pods/ready", "{\"podId\":\"pod-2\"}", null, "Bearer " + TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void readOnlyEndpointsStayOpenForMonitoring() throws Exception {
        assertThat(get("/healthz").statusCode()).isEqualTo(200);
        assertThat(get("/metrics").statusCode()).isEqualTo(200);
        assertThat(get("/infra").statusCode()).isEqualTo(200);
    }

    @Test
    void rejectedRequestDoesNotRegisterAReadyPod() throws Exception {
        post("/pods/ready", "{\"podId\":\"pod-evil\",\"arenaGroup\":\"solo\"}", null, null);
        assertThat(get("/metrics").body()).doesNotContain("pod-evil");
        // And an authenticated one does.
        post("/pods/ready", "{\"podId\":\"pod-good\",\"arenaGroup\":\"solo\"}", TOKEN, null);
        assertThat(get("/metrics").body()).contains("bedwars_free_slots{group=\"solo\"} 25");
    }

    @Test
    void queueEndpointRequiresTheToken() throws Exception {
        String body = "{\"player\":\"" + java.util.UUID.randomUUID()
                + "\",\"username\":\"alice\",\"priority\":0,\"requestedAtMillis\":" + System.currentTimeMillis() + "}";
        assertThat(post("/lobby/queue", body, null, null).statusCode()).isEqualTo(401);
        assertThat(post("/lobby/queue", body, TOKEN, null).statusCode()).isEqualTo(200);
    }

    @Test
    void infraEndpointDescribesTheProvisionerWithoutLeakingTheToken() throws Exception {
        HttpResponse<String> response = get("/infra");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"authenticated\":true");
        assertThat(response.body()).doesNotContain(TOKEN);
    }

    @Test
    void optionalUsernameIsCarriedThrough() throws Exception {
        // Sanity: the existing open path still deserialises a full QueueRequest.
        var request = new dev.bedwars.api.dto.QueueRequest(java.util.UUID.randomUUID(), "bob", 0,
                Optional.of("solo"), Optional.empty(), System.currentTimeMillis());
        HttpResponse<String> response = post("/lobby/queue",
                dev.bedwars.api.json.JsonSupport.gson().toJson(request), TOKEN, null);
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
