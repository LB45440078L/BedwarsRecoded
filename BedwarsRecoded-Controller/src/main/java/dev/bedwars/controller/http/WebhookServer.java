package dev.bedwars.controller.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.api.service.DispatchResult;
import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.pod.ReadyPodRegistry;
import dev.bedwars.controller.queue.QueueManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The controller's HTTP surface:
 * <ul>
 *   <li>{@code POST /pods/*} — pods report ready/started/ended/heartbeat/draining</li>
 *   <li>{@code POST /lobby/queue} — lobby requests a slot (capacity-aware dispatch)</li>
 *   <li>{@code GET /healthz}, {@code GET /queue/depth}</li>
 * </ul>
 * Uses the JDK's built-in HTTP server, so no extra runtime dependency.
 */
public final class WebhookServer {

    private static final long DISPATCH_WAIT_MILLIS = 1_500L;

    private final int port;
    private final QueueManager queueManager;
    private final ReadyPodRegistry registry;
    private final ControllerConfig config;
    private final Gson gson = JsonSupport.gson();
    private final Logger log;
    private HttpServer server;
    private ExecutorService executor;

    public WebhookServer(int port, QueueManager queueManager, ReadyPodRegistry registry,
                         ControllerConfig config, Logger log) {
        this.port = port;
        this.queueManager = queueManager;
        this.registry = registry;
        this.config = config;
        this.log = log;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/pods/ready", exchange -> handle(exchange, this::podReady));
        server.createContext("/pods/started", exchange -> handle(exchange, this::logOnly));
        server.createContext("/pods/ended", exchange -> handle(exchange, this::podGone));
        server.createContext("/pods/heartbeat", exchange -> handle(exchange, this::logOnly));
        server.createContext("/pods/draining", exchange -> handle(exchange, this::podGone));
        server.createContext("/lobby/queue", exchange -> handle(exchange, this::lobbyQueue));
        server.createContext("/queue/depth", exchange -> respond(exchange, 200, gson.toJson(queueManager.depthByGroup())));
        server.createContext("/lobby/arena-status", exchange -> respond(exchange, 200, gson.toJson(arenaStatus())));
        server.createContext("/metrics", exchange -> respondText(exchange, 200, metrics()));
        server.createContext("/healthz", exchange -> respond(exchange, 200, "ok"));
        server.start();
        log.info("Controller HTTP listening on :{}", port);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.close();
        }
    }

    private String podReady(JsonObject body) {
        String podId = body.get("podId").getAsString();
        String group = body.has("arenaGroup") && !body.get("arenaGroup").isJsonNull()
                ? body.get("arenaGroup").getAsString() : "any";
        registry.registerReady(podId, group);
        log.info("Pod {} READY for group {}", podId, group);
        queueManager.drain(); // new capacity: try to place waiting players
        return "{\"accepted\":true}";
    }

    private String logOnly(JsonObject body) {
        log.info("Controller received: {}", body);
        return "{\"accepted\":true}";
    }

    /**
     * A pod that reports draining or ended is leaving the pool: remove it so a
     * later queue request is never dispatched to a pod that no longer exists.
     */
    private String podGone(JsonObject body) {
        if (body.has("podId") && !body.get("podId").isJsonNull()) {
            String podId = body.get("podId").getAsString();
            registry.markGone(podId);
            log.info("Pod {} removed from the ready pool; {}", podId, body);
        } else {
            log.info("Controller received (no podId): {}", body);
        }
        return "{\"accepted\":true}";
    }

    private String lobbyQueue(JsonObject body) {
        QueueRequest request = gson.fromJson(body, QueueRequest.class);
        if (request == null) {
            return gson.toJson(DispatchResult.retry(config.baseBackoffMillis()));
        }
        try {
            DispatchResult result = queueManager.enqueue(request).get(DISPATCH_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            return gson.toJson(result);
        } catch (TimeoutException e) {
            // No capacity within the wait window: tell the client to back off.
            return gson.toJson(DispatchResult.retry(queueManager.backoffMillis(queueManager.totalDepth())));
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return gson.toJson(DispatchResult.retry(config.baseBackoffMillis()));
        }
    }

    private interface Handler {
        String handle(JsonObject body);
    }

    private void handle(HttpExchange exchange, Handler handler) throws IOException {
        try (exchange) {
            JsonObject body = readJson(exchange);
            String response = handler.handle(body);
            respond(exchange, 200, response);
        } catch (RuntimeException e) {
            log.warn("Request handling failed: {}", e.getMessage());
            respond(exchange, 400, "{\"error\":\"bad_request\"}");
        }
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? new JsonObject() : gson.fromJson(text, JsonObject.class);
        }
    }

    /** Prometheus text exposition: the metrics KEDA scales on. */
    private String metrics() {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP bedwars_queue_depth Players waiting per arena group.\n");
        sb.append("# TYPE bedwars_queue_depth gauge\n");
        queueManager.depthByGroup().forEach((group, depth) ->
                sb.append("bedwars_queue_depth{group=\"").append(group).append("\"} ").append(depth).append('\n'));
        sb.append("# HELP bedwars_ready_pods READY pods available per arena group.\n");
        sb.append("# TYPE bedwars_ready_pods gauge\n");
        registry.readyByGroup().forEach((group, count) ->
                sb.append("bedwars_ready_pods{group=\"").append(group).append("\"} ").append(count).append('\n'));
        return sb.toString();
    }

    /**
     * Live per-group state for lobby NPC/sign displays: how many pods are ready
     * and how many players are waiting. The lobby polls (or the controller pushes)
     * this so NPCs show current counts without querying the game pods.
     */
    private Map<String, Map<String, Integer>> arenaStatus() {
        Map<String, Integer> ready = registry.readyByGroup();
        Map<String, Integer> depth = queueManager.depthByGroup();
        Set<String> groups = new TreeSet<>(ready.keySet());
        groups.addAll(depth.keySet());
        Map<String, Map<String, Integer>> status = new LinkedHashMap<>();
        for (String group : groups) {
            status.put(group, Map.of(
                    "ready", ready.getOrDefault(group, 0),
                    "queued", depth.getOrDefault(group, 0)));
        }
        return status;
    }

    private void respondText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}