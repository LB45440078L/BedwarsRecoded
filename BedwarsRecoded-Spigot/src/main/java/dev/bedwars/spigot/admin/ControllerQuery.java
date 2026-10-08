package dev.bedwars.spigot.admin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.bedwars.api.json.JsonSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only view of the controller, for in-game operator tooling.
 *
 * <p>This exists so an admin can answer "what is happening?" from inside the game:
 * how many servers exist, what is free, who is waiting. The alternative — ssh into the
 * host and read {@code docker ps} — is what this replaces, and it is no use at all to
 * someone who is already in the world debugging a queue that is not moving.
 *
 * <p>Every call is asynchronous and bounded: a controller that is down or slow leaves
 * the caller with an empty result to report, never a stalled main thread. The shared
 * secret is presented when one is configured, and is never logged.
 */
public final class ControllerQuery {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-admin");
    private static final String TOKEN_HEADER = "X-Bedwars-Token";

    private final String baseUrl;
    private final String apiToken;
    private final Gson gson = JsonSupport.gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public ControllerQuery(String baseUrl, String apiToken) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.apiToken = apiToken == null ? "" : apiToken;
    }

    /** True when the plugin knows where the controller is. */
    public boolean configured() {
        return !baseUrl.isBlank();
    }

    /** Whether a shared secret is presented. The value itself is never exposed. */
    public boolean authenticated() {
        return !apiToken.isBlank();
    }

    public CompletableFuture<JsonObject> infra() {
        return get("/infra");
    }

    public CompletableFuture<JsonObject> servers() {
        return get("/servers");
    }

    public CompletableFuture<JsonObject> queueDepth() {
        return get("/queue/depth");
    }

    public CompletableFuture<JsonObject> arenaStatus() {
        return get("/lobby/arena-status");
    }

    /** Never completes exceptionally: an unreachable controller yields an empty object. */
    private CompletableFuture<JsonObject> get(String path) {
        if (!configured()) {
            return CompletableFuture.completedFuture(new JsonObject());
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (!apiToken.isBlank()) {
            builder.header(TOKEN_HEADER, apiToken);
        }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        LOG.warn("Controller returned {} for {}", response.statusCode(), path);
                        return new JsonObject();
                    }
                    JsonObject parsed = gson.fromJson(response.body(), JsonObject.class);
                    return parsed == null ? new JsonObject() : parsed;
                })
                .exceptionally(error -> {
                    LOG.warn("Controller query {} failed: {}", path, error.toString());
                    return new JsonObject();
                });
    }
}
