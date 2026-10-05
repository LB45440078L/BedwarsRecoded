package dev.bedwars.spigot.report;

import com.google.gson.Gson;
import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.api.service.PodHeartbeat;
import dev.bedwars.api.service.PodReporter;
import dev.bedwars.core.reporting.ReportingPolicy;
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
 * Reports pod state to the controller over HTTP. All calls are fire-and-forget on a
 * virtual thread: a controller outage must never stall the game tick.
 *
 * <p>Calls are gated by a {@link ReportingPolicy}. When reporting is off — an ordinary
 * standalone server, or a pod in {@code AUTO} mode that found no controller at boot —
 * <b>nothing is sent at all</b>. When reporting is on but the controller is down, the
 * first failure is logged, the rest are throttled, and after a few consecutive
 * failures reporting gives up for the session rather than logging a connection error
 * on every heartbeat.
 */
public final class HttpPodReporter implements PodReporter {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-pod");

    private final String baseUrl;
    private final ReportingPolicy policy;
    private final Gson gson = JsonSupport.gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public HttpPodReporter(String baseUrl) {
        this(baseUrl, new ReportingPolicy(true));
    }

    public HttpPodReporter(String baseUrl, ReportingPolicy policy) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.policy = policy;
    }

    /** The policy in use, so the bootstrap can log the effective decision. */
    public ReportingPolicy policy() {
        return policy;
    }

    @Override
    public void reportReady(String podId, String arenaGroup, TemplateDescriptor template, String gameId) {
        post("/pods/ready", fields(
                "podId", podId,
                "arenaGroup", arenaGroup,
                "gameId", gameId,
                "template", template.coordinate(),
                "phase", "READY"));
    }

    @Override
    public void reportGameStarted(String gameId, int playerCount) {
        post("/pods/started", fields("gameId", gameId, "playerCount", playerCount));
    }

    @Override
    public void reportGameEnded(String podId, GameResult result) {
        post("/pods/ended", fields("podId", podId, "result", result));
    }

    @Override
    public void heartbeat(PodHeartbeat heartbeat) {
        post("/pods/heartbeat", heartbeat);
    }

    @Override
    public void reportDraining(String podId, String gameId, int remainingPlayers) {
        post("/pods/draining", fields(
                "podId", podId,
                "gameId", gameId,
                "remainingPlayers", remainingPlayers,
                "phase", "DRAINING"));
    }

    /** Null-tolerant map builder ({@code Map.of} rejects a null podId, e.g. a local run). */
    private static Map<String, Object> fields(Object... keyValuePairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            if (keyValuePairs[i + 1] != null) {
                map.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
            }
        }
        return map;
    }

    private void post(String path, Object body) {
        if (!policy.shouldSend()) {
            return; // standalone / disabled: not a single network call
        }
        String json = gson.toJson(body);
        executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
                policy.recordSuccess();
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                boolean logNow = policy.shouldLogFailure(now);
                boolean disabledNow = policy.recordFailure(now);
                if (disabledNow) {
                    LOG.warn("controller_unreachable url={} - reporting disabled for this session after {} "
                            + "consecutive failures (use deployment.mode: STANDALONE to silence this entirely)",
                            baseUrl, policy.consecutiveFailures());
                } else if (logNow) {
                    LOG.warn("report_failed path={} error={}", path, e.toString());
                }
            }
        });
    }
}