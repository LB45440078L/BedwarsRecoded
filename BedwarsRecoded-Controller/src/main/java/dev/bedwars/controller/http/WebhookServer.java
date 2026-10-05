package dev.bedwars.controller.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.json.JsonSupport;
import dev.bedwars.api.service.DispatchResult;
import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.pod.ServerRegistry;
import dev.bedwars.controller.provision.ServerProvisioner;
import dev.bedwars.controller.queue.QueueManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 *   <li>{@code GET /healthz}, {@code /queue/depth}, {@code /lobby/arena-status},
 *       {@code /infra}, {@code /metrics}</li>
 * </ul>
 * Uses the JDK's built-in HTTP server, so no extra runtime dependency.
 *
 * <p><b>Security.</b> Mutating endpoints require a shared secret when one is
 * configured ({@code BEDWARS_API_TOKEN}). This protects a controller that is
 * reachable from game servers on other machines. When the token is blank the
 * controller logs a loud warning and stays open, which is the documented
 * single-host development mode. The secret is compared in constant time and is
 * never logged.
 */
public final class WebhookServer {

    private static final long DISPATCH_WAIT_MILLIS = 1_500L;
    private static final String TOKEN_HEADER = "X-Bedwars-Token";

    private final int port;
    private final QueueManager queueManager;
    private final ServerRegistry registry;
    private final ServerProvisioner provisioner;
    private final ControllerConfig config;
    private final Gson gson = JsonSupport.gson();
    private final Logger log;
    private HttpServer server;
    private ExecutorService executor;

    public WebhookServer(int port, QueueManager queueManager, ServerRegistry registry,
                         ControllerConfig config, ServerProvisioner provisioner, Logger log) {
        this.port = port;
        this.queueManager = queueManager;
        this.registry = registry;
        this.config = config;
        this.provisioner = provisioner;
        this.log = log;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/pods/ready", exchange -> handle(exchange, this::podReady));
        server.createContext("/pods/capacity", exchange -> handle(exchange, this::podCapacity));
        server.createContext("/pods/started", exchange -> handle(exchange, this::logOnly));
        server.createContext("/pods/ended", exchange -> handle(exchange, this::podEnded));
        server.createContext("/pods/heartbeat", exchange -> handle(exchange, this::logOnly));
        server.createContext("/pods/draining", exchange -> handle(exchange, this::podGone));
        server.createContext("/lobby/queue", exchange -> handle(exchange, this::lobbyQueue));
        server.createContext("/queue/depth", exchange -> respond(exchange, 200, gson.toJson(queueManager.depthByGroup())));
        server.createContext("/lobby/arena-status", exchange -> respond(exchange, 200, gson.toJson(arenaStatus())));
        server.createContext("/infra", exchange -> respond(exchange, 200, gson.toJson(infraStatus())));
        server.createContext("/metrics", exchange -> respondText(exchange, 200, metrics()));
        server.createContext("/healthz", exchange -> respond(exchange, 200, "ok"));
        server.start();
        if (config.security().enabled()) {
            log.info("Controller HTTP listening on :{} (mutating endpoints require a token)", port);
        } else {
            log.warn("Controller HTTP listening on :{} WITHOUT authentication. "
                    + "Set BEDWARS_API_TOKEN before exposing this controller to other machines.", port);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.close();
        }
    }

    /** A server reports it is up and can host {@code gamesPerServer} matches. */
    private String podReady(JsonObject body) {
        String podId = body.get("podId").getAsString();
        String group = body.has("arenaGroup") && !body.get("arenaGroup").isJsonNull()
                ? body.get("arenaGroup").getAsString() : "any";
        int capacity = body.has("capacity") && !body.get("capacity").isJsonNull()
                ? body.get("capacity").getAsInt() : config.provisioning().gamesPerServer();
        registry.register(podId, group, capacity);
        log.info("Server {} READY for group {} (capacity {} matches)", podId, group, capacity);
        queueManager.drain(); // new capacity: try to place waiting players
        return "{\"accepted\":true}";
    }

    /** A running server reports how many match slots it currently has free. */
    private String podCapacity(JsonObject body) {
        if (!body.has("podId") || body.get("podId").isJsonNull()) {
            throw new IllegalArgumentException("podId required");
        }
        String podId = body.get("podId").getAsString();
        int free = body.has("freeSlots") && !body.get("freeSlots").isJsonNull()
                ? body.get("freeSlots").getAsInt() : 0;
        registry.updateFreeSlots(podId, free);
        log.info("Server {} capacity report: {} free match slots", podId, free);
        queueManager.drain();
        return "{\"accepted\":true}";
    }

    private String logOnly(JsonObject body) {
        log.info("Controller received: {}", body);
        return "{\"accepted\":true}";
    }

    /** A match ended on the server: the slot is free again; the server stays in the pool. */
    private String podEnded(JsonObject body) {
        if (body.has("podId") && !body.get("podId").isJsonNull()) {
            String podId = body.get("podId").getAsString();
            registry.releaseSlot(podId);
            log.info("Match ended on {}; slot released. {}", podId, body);
            queueManager.drain();
        } else {
            log.info("Controller received (no podId): {}", body);
        }
        return "{\"accepted\":true}";
    }

    /**
     * A server that reports draining is leaving the pool: remove it so a later queue
     * request is never dispatched to a server that is shutting down.
     */
    private String podGone(JsonObject body) {
        if (body.has("podId") && !body.get("podId").isJsonNull()) {
            String podId = body.get("podId").getAsString();
            registry.markGone(podId);
            log.info("Server {} removed from the pool; {}", podId, body);
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
        try {
            if (!authorized(exchange)) {
                log.warn("Rejected unauthenticated request to {}", exchange.getRequestURI());
                respond(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            JsonObject body = readJson(exchange);
            String response = handler.handle(body);
            respond(exchange, 200, response);
        } catch (RuntimeException e) {
            // A malformed body must produce a real 400. Do NOT use try-with-resources
            // here: it closes the exchange before this catch runs, so the error
            // response would fail and the client would see an empty reply.
            log.warn("Request handling failed: {}", e.getMessage());
            respond(exchange, 400, "{\"error\":\"bad_request\"}");
        } finally {
            exchange.close();
        }
    }

    /** Constant-time shared-secret check. Returns true when no token is configured. */
    private boolean authorized(HttpExchange exchange) {
        if (!config.security().enabled()) {
            return true;
        }
        String presented = exchange.getRequestHeaders().getFirst(TOKEN_HEADER);
        if (presented == null) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                presented = auth.substring(7).trim();
            }
        }
        return presented != null && MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                config.security().apiToken().getBytes(StandardCharsets.UTF_8));
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
        sb.append("# HELP bedwars_free_slots Free match slots per arena group.\n");
        sb.append("# TYPE bedwars_free_slots gauge\n");
        registry.freeSlotsByGroup().forEach((group, free) ->
                sb.append("bedwars_free_slots{group=\"").append(group).append("\"} ").append(free).append('\n'));
        sb.append("# HELP bedwars_servers Provisioned game servers.\n");
        sb.append("# TYPE bedwars_servers gauge\n");
        sb.append("bedwars_servers ").append(provisioner.currentServers()).append('\n');
        sb.append("# HELP bedwars_idle_servers Servers with no match running.\n");
        sb.append("# TYPE bedwars_idle_servers gauge\n");
        sb.append("bedwars_idle_servers ").append(registry.idleServers()).append('\n');
        sb.append("# HELP bedwars_game_capacity Total concurrent game slots.\n");
        sb.append("# TYPE bedwars_game_capacity gauge\n");
        sb.append("bedwars_game_capacity ").append(provisioner.totalGameCapacity()).append('\n');
        return sb.toString();
    }

    /** Infrastructure view for operators and the deploy verifier. Never leaks secrets. */
    private Map<String, Object> infraStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("provisioner", provisioner.kind().name());
        status.put("description", provisioner.describe());
        status.put("servers", provisioner.currentServers());
        status.put("registeredServers", registry.serverCount());
        status.put("freeSlots", registry.totalFreeSlots());
        status.put("minServers", provisioner.minimumServers());
        status.put("maxServers", provisioner.maximumServers());
        status.put("gamesPerServer", provisioner.gamesPerServer());
        status.put("gameCapacity", provisioner.totalGameCapacity());
        status.put("authenticated", config.security().enabled());
        return status;
    }

    /**
     * Live per-group state for lobby NPC/sign displays: how many pods are ready
     * and how many players are waiting. The lobby polls (or the controller pushes)
     * this so NPCs show current counts without querying the game pods.
     */
    private Map<String, Map<String, Integer>> arenaStatus() {
        Map<String, Integer> free = registry.freeSlotsByGroup();
        Map<String, Integer> depth = queueManager.depthByGroup();
        Set<String> groups = new TreeSet<>(free.keySet());
        groups.addAll(depth.keySet());
        Map<String, Map<String, Integer>> status = new LinkedHashMap<>();
        for (String group : groups) {
            status.put(group, Map.of(
                    "freeSlots", free.getOrDefault(group, 0),
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
