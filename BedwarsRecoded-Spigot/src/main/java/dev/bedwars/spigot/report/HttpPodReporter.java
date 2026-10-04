package dev.bedwars.spigot.report;

import com.google.gson.Gson;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.PodHeartbeat;
import dev.bedwars.api.service.PodReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reports pod state to the controller over HTTP. All calls are fire-and-forget on
 * a virtual thread: a controller outage must never stall the game tick.
 */
public final class HttpPodReporter implements PodReporter {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-pod");

    private final String baseUrl;
    private final Gson gson = JsonSupport.gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HttpPodReporter(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public void reportReady(String podId, String arenaGroup, TemplateDescriptor template, String gameId) {
        post("/pods/ready", Map.of(
                "podId", podId,
                "arenaGroup", arenaGroup,
                "gameId", gameId,
                "template", template.coordinate(),
                "phase", "READY"));
    }

    @Override
    public void reportGameStarted(String gameId, int playerCount) {
        post("/pods/started", Map.of("gameId", gameId, "playerCount", playerCount));
    }

    @Override
    public void reportGameEnded(String podId, GameResult result) {
        post("/pods/ended", Map.of("podId", podId, "result", result));
    }

    @Override
    public void heartbeat(PodHeartbeat heartbeat) {
        post("/pods/heartbeat", heartbeat);
    }

    @Override
    public void reportDraining(String podId, String gameId, int remainingPlayers) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("podId", podId);
        payload.put("gameId", gameId);
        payload.put("remainingPlayers", remainingPlayers);
        payload.put("phase", "DRAINING");
        post("/pods/draining", payload);
    }

    private void post(String path, Object body) {
        String json = gson.toJson(body);
        executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // Never stall the tick, but never silently drop either: a controller
                // outage must be visible in the pod log.
                LOG.warn("report_failed path={} error={}", path, e.toString());
            }
        });
    }
}