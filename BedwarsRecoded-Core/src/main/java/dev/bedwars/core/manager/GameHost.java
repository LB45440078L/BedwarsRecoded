package dev.bedwars.core.manager;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.core.config.ArenaDefinition;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GameState;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.event.EventBus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs <em>many</em> concurrent matches on one dedicated server.
 *
 * <p>This is what turns "one pod = one match" into "one server = up to N matches":
 * {@link #join} places a player into the emptiest match that still has room, creating a
 * new match only when none can take them and the server is below {@link #maxGames}.
 * Matchmaking capacity is therefore a count of free <em>match slots</em>, not whole
 * servers, which is exactly what the controller's capacity model needs.
 *
 * <p>Bukkit-free and single-threaded by contract: the server's main thread owns it, so
 * every mutating method is {@code synchronized} to make that contract explicit and to
 * survive any accidental cross-thread call.
 */
public final class GameHost {

    private final ArenaDefinition arena;
    private final TemplateDescriptor template;
    private final EventBus bus;
    private final GameManager manager;
    private final int maxGames;
    private final int respawnDelaySeconds;
    private final String idPrefix;
    private final Map<String, Game> games = new LinkedHashMap<>();
    private int sequence;

    public GameHost(ArenaDefinition arena,
                    TemplateDescriptor template,
                    EventBus bus,
                    GameManager manager,
                    int maxGames,
                    int respawnDelaySeconds,
                    String idPrefix) {
        this.arena = arena;
        this.template = template;
        this.bus = bus;
        this.manager = manager;
        this.maxGames = Math.max(1, maxGames);
        this.respawnDelaySeconds = respawnDelaySeconds;
        this.idPrefix = idPrefix == null || idPrefix.isBlank() ? "game" : idPrefix;
    }

    /**
     * Places a player into a match, creating one if needed.
     *
     * @return the match they joined, or empty when every slot is taken/started
     */
    public synchronized Optional<Game> join(UUID uuid, String username, long nowMillis) {
        Game existing = manager.byPlayer(uuid).orElse(null);
        if (existing != null) {
            return Optional.of(existing);
        }
        Game target = games.values().stream()
                .filter(GameHost::acceptingPlayers)
                .min(Comparator.comparingInt(Game::playerCount))
                .orElse(null);
        if (target == null) {
            if (games.size() >= maxGames) {
                return Optional.empty();
            }
            target = createGame(nowMillis);
        }
        target.addPlayer(uuid, username);
        manager.trackPlayer(uuid, target.id());
        return Optional.of(target);
    }

    /** Creates a new match from the arena template, honouring {@link #maxGames}. */
    public synchronized Game createGame(long nowMillis) {
        String id = idPrefix + "-" + (++sequence);
        Game game = GameFactory.create(id, arena, template, bus, respawnDelaySeconds, nowMillis);
        games.put(id, game);
        manager.register(game);
        return game;
    }

    public synchronized Optional<Game> byId(String gameId) {
        return Optional.ofNullable(games.get(gameId));
    }

    public synchronized List<Game> games() {
        return List.copyOf(games.values());
    }

    public synchronized int gameCount() {
        return games.size();
    }

    public synchronized int maxGames() {
        return maxGames;
    }

    public ArenaDefinition arena() {
        return arena;
    }

    /** Matches that have started and not yet finished. */
    public synchronized int inProgressGames() {
        return (int) games.values().stream()
                .filter(g -> g.state() == GameState.COUNTDOWN || g.state() == GameState.RUNNING
                        || g.state() == GameState.SUDDEN_DEATH)
                .count();
    }

    /** Matches accepting players right now. */
    public synchronized int joinableGames() {
        return (int) games.values().stream().filter(GameHost::acceptingPlayers).count();
    }

    /**
     * How many <em>more</em> matches this server can create. A match consumes its slot
     * from creation until it is pruned, so this is {@code maxGames - liveMatches} — the
     * figure the controller uses as the server's remaining capacity. Reporting it from
     * started matches alone would let the controller over-book a server whose slots are
     * already held by waiting matches.
     */
    public synchronized int freeSlots() {
        return Math.max(0, maxGames - games.size());
    }

    /** Drops finished matches from the registry so their ids can be reused. */
    public synchronized void pruneFinished() {
        games.values().removeIf(game -> {
            if (game.state().isTerminal()) {
                manager.unregister(game.id());
                return true;
            }
            return false;
        });
    }

    private static boolean acceptingPlayers(Game game) {
        if (game.state() != GameState.WAITING && game.state() != GameState.COUNTDOWN) {
            return false;
        }
        return game.teams().stream().anyMatch(team -> !team.isEliminated() && !team.isFull());
    }
}
