package dev.bedwars.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.bedwars.api.service.DispatchResult;
import dev.bedwars.velocity.client.ControllerClient;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The network front door.
 *
 * <p>Players always connect to the proxy first. What happens next is the whole point of
 * this class:
 *
 * <ol>
 *   <li><b>Login</b> — the player is placed on the <em>lobby</em>, never straight into a
 *       match. The lobby is a normal, persistent server where players stand around and
 *       choose what to play.</li>
 *   <li><b>Queue</b> — the lobby asks for a match (by sign, NPC or {@code /bw queue}) and
 *       forwards that request to the proxy over a plugin message. The proxy asks the
 *       controller, and when the controller names a game server, the proxy transfers the
 *       player onto it. Only the proxy can move a player between servers, which is why
 *       the lobby never talks to the controller itself.</li>
 *   <li><b>Return</b> — when a match ends, the game server asks the proxy to send its
 *       players back to the lobby. The loop closes: lobby -> match -> lobby.</li>
 * </ol>
 *
 * <p>Game servers are ephemeral and have no static entry in {@code velocity.toml}, so a
 * pod named by the controller is registered with the proxy the first time it is used
 * ({@link #serverForPod}).
 */
@Plugin(id = "bedwarsrecoded", name = "BedwarsRecoded", version = "1.0.0-SNAPSHOT",
        description = "Routes players: lobby first, then a match, then back to the lobby.",
        authors = {"BedwarsRecoded"})
public final class BedwarsVelocityPlugin {

    private static final String CHANNEL_QUEUE = "bedwars:queue";
    private static final String CHANNEL_RETURN = "bedwars:return";
    private static final String CHANNEL_LEAVE = "bedwars:leave";
    private static final int DEFAULT_GAME_PORT = 25565;

    private final ProxyServer proxy;
    private final Logger logger;

    private ControllerClient controller;

    /** In-flight queue requests, so one player cannot stack up several. */
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    /**
     * The repeating "you are still in the queue" task per waiting player.
     *
     * <p>A player who queues used to be told once and then left staring at nothing, with no
     * way to tell "searching" from "broken". The status line is the difference.
     */
    private final Map<UUID, ScheduledTask> feedback = new ConcurrentHashMap<>();

    /** How often the waiting-status line refreshes. */
    private static final long FEEDBACK_INTERVAL_MILLIS = 2_000L;

    @Inject
    public BedwarsVelocityPlugin(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        String baseUrl = env("CONTROLLER_URL", "http://bedwars-controller:8080");
        // The same shared secret the game servers present. Blank = the controller runs
        // open. Only ever reported as a yes/no: the value never reaches a log.
        String apiToken = env("BEDWARS_API_TOKEN", "");
        this.controller = new ControllerClient(baseUrl, apiToken);
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.create("bedwars", "queue"));
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.create("bedwars", "return"));
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.create("bedwars", "leave"));
        logger.info("BedwarsRecoded proxy initialised; controller={} controller_auth={} lobby={} pod_suffix='{}'",
                baseUrl, apiToken.isBlank() ? "none" : "token", lobbyServer(), podSuffix());
        if (proxy.getServer(lobbyServer()).isEmpty()) {
            logger.warn("Lobby server '{}' is NOT registered with this proxy. Players would have nowhere "
                    + "to land. Check the lobby Deployment/Service and the velocity.toml [servers] block.",
                    lobbyServer());
        }
    }

    /**
     * Players land in the lobby. This used to push every player straight into a match,
     * which left anyone who logged in while the fleet was at zero with no server at all.
     */
    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        proxy.getServer(lobbyServer()).ifPresentOrElse(
                lobby -> player.createConnectionRequest(lobby).fireAndForget(),
                () -> player.sendMessage(Component.text(
                        "The lobby is unavailable right now - please try again shortly.", NamedTextColor.RED)));
    }

    /** A player who logs out must not keep a phantom queue entry inflating the depth. */
    @Subscribe
    public void onDisconnect(com.velocitypowered.api.event.connection.DisconnectEvent event) {
        abandonQueue(event.getPlayer());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!(event.getSource() instanceof ServerConnection source)) {
            return; // sent by the proxy itself, not by a backend server
        }
        String channel = event.getIdentifier().getId();
        if (CHANNEL_QUEUE.equals(channel)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            handleQueue(source, new String(event.getData(), StandardCharsets.UTF_8).trim());
        } else if (CHANNEL_RETURN.equals(channel)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            handleReturn(source, new String(event.getData(), StandardCharsets.UTF_8).trim());
        } else if (CHANNEL_LEAVE.equals(channel)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            handleLeave(new String(event.getData(), StandardCharsets.UTF_8).trim());
        }
    }

    // ---- queueing --------------------------------------------------------

    /** A backend asked for a match for one of its players. */
    private void handleQueue(ServerConnection source, String uuidText) {
        UUID id;
        try {
            id = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring a malformed queue request from {}: '{}'", source.getServerInfo().getName(), uuidText);
            return;
        }
        Optional<Player> maybePlayer = proxy.getPlayer(id);
        if (maybePlayer.isEmpty()) {
            return; // they disconnected between clicking and the request arriving
        }
        Player player = maybePlayer.get();
        if (!queued.add(id)) {
            return; // already waiting
        }
        player.sendMessage(Component.text("Searching for a solo match...", NamedTextColor.YELLOW)
                .append(Component.text(" you will be moved automatically when a game frees up.",
                        NamedTextColor.GRAY)));
        startFeedback(player);
        requestSlot(player, 0);
    }

    /**
     * Keeps a waiting player informed: how many are queueing and what capacity exists.
     * Purely informational, so a failed status fetch leaves the previous line alone.
     */
    private void startFeedback(Player player) {
        stopFeedback(player.getUniqueId());
        ScheduledTask task = proxy.getScheduler()
                .buildTask(this, () -> showQueueStatus(player))
                .repeat(Duration.ofMillis(FEEDBACK_INTERVAL_MILLIS))
                .schedule();
        feedback.put(player.getUniqueId(), task);
    }

    private void stopFeedback(UUID player) {
        ScheduledTask task = feedback.remove(player);
        if (task != null) {
            task.cancel();
        }
    }

    private void showQueueStatus(Player player) {
        if (!queued.contains(player.getUniqueId()) || !player.isActive()) {
            stopFeedback(player.getUniqueId());
            return;
        }
        controller.arenaStatus().thenAccept(status -> {
            if (!queued.contains(player.getUniqueId()) || !player.isActive()) {
                return;
            }
            int waiting = status.values().stream().mapToInt(ControllerClient.GroupStatus::queued).sum();
            int free = status.values().stream().mapToInt(ControllerClient.GroupStatus::freeSlots).sum();
            int servers = status.values().stream().mapToInt(ControllerClient.GroupStatus::servers).sum();
            String capacity = free > 0
                    ? free + (free == 1 ? " match slot free" : " match slots free")
                    : "no free slot yet - starting a server";
            player.sendActionBar(Component.text("Searching for a match", NamedTextColor.YELLOW)
                    .append(Component.text("  |  " + waiting + " in queue  |  " + servers
                            + (servers == 1 ? " server  |  " : " servers  |  ") + capacity, NamedTextColor.GRAY)));
        });
    }

    /** The player stopped waiting: drop the entry on the controller so the depth stays true. */
    private void abandonQueue(Player player) {
        if (queued.remove(player.getUniqueId())) {
            stopFeedback(player.getUniqueId());
            controller.dequeue(player.getUniqueId());
        }
    }

    /** A backend cancelled one of its players' queue requests. */
    private void handleLeave(String uuidText) {
        UUID id;
        try {
            id = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring a malformed queue cancellation: '{}'", uuidText);
            return;
        }
        proxy.getPlayer(id).ifPresent(this::abandonQueue);
    }

    /**
     * Asks the controller for a slot, retrying with the delay it asks for.
     *
     * <p>The retry lives here rather than in the lobby because the lobby would have to
     * poll blindly: the controller's {@code retryAfterMillis} is the backoff it actually
     * wants, and only the proxy can act on the answer.
     */
    private void requestSlot(Player player, int attempt) {
        controller.requestSlot(player.getUniqueId(), player.getUsername(), Optional.empty())
                .thenAccept(result -> onDispatch(player, attempt, result))
                .exceptionally(error -> {
                    logger.warn("Controller request failed for {}: {}", player.getUsername(), error.toString());
                    queued.remove(player.getUniqueId());
                    player.sendMessage(Component.text("Matchmaking is unavailable right now.", NamedTextColor.RED));
                    return null;
                });
    }

    private void onDispatch(Player player, int attempt, DispatchResult result) {
        if (result.successful()) {
            stopFeedback(player.getUniqueId());
            queued.remove(player.getUniqueId());
            transferToPod(player, result.podAddress());
            return;
        }
        // Patient by default: waiting a couple of minutes for a server to boot is normal,
        // and giving up after a few seconds read as "queueing does nothing".
        int maxAttempts = intEnv("QUEUE_RETRY_ATTEMPTS", 30);
        if (attempt >= maxAttempts) {
            stopFeedback(player.getUniqueId());
            queued.remove(player.getUniqueId());
            controller.dequeue(player.getUniqueId());
            player.sendMessage(Component.text("No match was available after a few minutes - please try again.",
                    NamedTextColor.RED));
            logger.info("Gave up queueing {} after {} attempts", player.getUsername(), attempt);
            return;
        }
        long delayMillis = Math.max(longEnv("QUEUE_RETRY_MILLIS", 1_000L), result.retryAfterMillis());
        proxy.getScheduler().buildTask(this, () -> {
            if (player.isActive()) {
                requestSlot(player, attempt + 1);
            } else {
                queued.remove(player.getUniqueId());
            }
        }).delay(Duration.ofMillis(delayMillis)).schedule();
    }

    // ---- moving players --------------------------------------------------

    /** Moves a player onto the game pod the controller named. */
    private void transferToPod(Player player, String podAddress) {
        if (podAddress == null || podAddress.isBlank()) {
            logger.warn("Controller returned an empty pod address for {}", player.getUsername());
            return;
        }
        RegisteredServer server = serverForPod(podAddress);
        player.sendMessage(Component.text("Match found", NamedTextColor.GREEN)
                .append(Component.text(" - sending you to " + server.getServerInfo().getName() + "...",
                        NamedTextColor.GRAY)));
        player.createConnectionRequest(server).fireAndForget();
        logger.info("Sending {} to game server {}", player.getUsername(), server.getServerInfo().getName());
    }

    /**
     * Resolves a pod name from the controller into a server the proxy can dial.
     *
     * <p>Game pods are ephemeral, so they cannot be listed in {@code velocity.toml}. The
     * controller hands out the pod's own id (which is its hostname), and the address is
     * that name plus {@code POD_ADDRESS_SUFFIX} — empty on a Docker network, where a
     * container name resolves on its own, and
     * {@code .bedwars-solo.bedwars.svc.cluster.local} in Kubernetes, where the
     * GameServerSet's headless Service gives every pod a DNS name.
     *
     * <p>The pod is registered with the proxy on first use and then reused.
     */
    private RegisteredServer serverForPod(String podAddress) {
        String parsedHost = podAddress;
        int parsedPort = DEFAULT_GAME_PORT;
        int colon = podAddress.lastIndexOf(':');
        if (colon > 0 && podAddress.indexOf('.') < colon) {
            try {
                parsedPort = Integer.parseInt(podAddress.substring(colon + 1));
                parsedHost = podAddress.substring(0, colon);
            } catch (NumberFormatException ignored) {
                // not a port; treat the whole string as a host
            }
        }
        final int port = parsedPort;
        final String name = parsedHost + podSuffix();
        return proxy.getServer(name).orElseGet(() -> {
            ServerInfo info = new ServerInfo(name, InetSocketAddress.createUnresolved(name, port));
            logger.info("Registering game pod '{}' with the proxy ({}:{})", name, name, port);
            return proxy.registerServer(info);
        });
    }

    /**
     * A match ended on a game server: send its players back to the lobby.
     *
     * <p>The payload is the list of players to move. An empty payload means "everyone on
     * the server that sent this" — useful, but a server hosting several matches uses the
     * explicit list so only the finished match's players are moved.
     */
    private void handleReturn(ServerConnection source, String payload) {
        Optional<RegisteredServer> lobby = proxy.getServer(lobbyServer());
        if (lobby.isEmpty()) {
            logger.warn("Asked to return players to '{}', but that server is not registered", lobbyServer());
            return;
        }
        List<UUID> only = parseUuids(payload);
        int moved = 0;
        for (Player player : source.getServer().getPlayersConnected()) {
            if (!only.isEmpty() && !only.contains(player.getUniqueId())) {
                continue;
            }
            player.createConnectionRequest(lobby.get()).fireAndForget();
            moved++;
        }
        logger.info("Returning {} player(s) from {} to the lobby", moved, source.getServerInfo().getName());
    }

    private static List<UUID> parseUuids(String csv) {
        if (csv.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new java.util.ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // skip anything that is not a UUID rather than failing the whole return
            }
        }
        return ids;
    }

    // ---- configuration ---------------------------------------------------

    private static String lobbyServer() {
        return env("LOBBY_SERVER", "lobby");
    }

    private static String podSuffix() {
        return env("POD_ADDRESS_SUFFIX", "");
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longEnv(String key, long fallback) {
        try {
            return Long.parseLong(env(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
