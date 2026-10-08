package dev.bedwars.velocity.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.api.dto.PartyInfo;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.service.DispatchResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Talks to the controller's lobby API. The proxy never decides routing itself —
 * it asks the controller where a player (or party) should go.
 */
public final class ControllerClient {

    /** The header the controller checks. Must match {@code WebhookServer.TOKEN_HEADER}. */
    private static final String TOKEN_HEADER = "X-Bedwars-Token";

    private final String baseUrl;
    private final String apiToken;
    private final Gson gson = JsonSupport.gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ControllerClient(String baseUrl) {
        this(baseUrl, "");
    }

    /**
     * @param apiToken the shared secret the controller requires, or blank when it runs
     *                 open. Blank sends no header: the controller rejects an empty one.
     */
    public ControllerClient(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiToken = apiToken == null ? "" : apiToken;
    }

    /** Whether this client presents a token. The value itself is never returned. */
    public boolean authenticated() {
        return !apiToken.isBlank();
    }

    public CompletableFuture<DispatchResult> requestSlot(UUID player, String username, Optional<PartyInfo> party) {
        QueueRequest request = new QueueRequest(player, username, 0, Optional.empty(), party,
                System.currentTimeMillis());
        String body = gson.toJson(request);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/lobby/queue"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json");
        if (!apiToken.isBlank()) {
            builder.header(TOKEN_HEADER, apiToken);
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parse(response.body()))
                .exceptionally(error -> DispatchResult.retry(1_000L));
    }

    private DispatchResult parse(String json) {
        JsonObject object = gson.fromJson(json, JsonObject.class);
        if (object == null || !object.has("podAddress") || object.get("podAddress").isJsonNull()) {
            return DispatchResult.retry(object != null && object.has("retryAfterMillis")
                    ? object.get("retryAfterMillis").getAsLong() : 1_000L);
        }
        String gameId = object.has("gameId") && !object.get("gameId").isJsonNull()
                ? object.get("gameId").getAsString() : null;
        return new DispatchResult(object.get("podAddress").getAsString(), gameId, java.util.List.of(), 0L);
    }
}