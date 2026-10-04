package dev.bedwars.core.manager;

import dev.bedwars.core.domain.Game;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single registry of live {@link Game} instances. Every event handler asks
 * this manager "which game owns this player/world?" before doing anything, which
 * is what keeps listeners thin and stateless.
 */
public final class GameManager {

    private final Map<String, Game> gamesById = new ConcurrentHashMap<>();
    private final Map<UUID, String> gameByPlayer = new ConcurrentHashMap<>();

    public void register(Game game) {
        gamesById.put(game.id(), game);
    }

    public void unregister(String gameId) {
        Game game = gamesById.remove(gameId);
        if (game != null) {
            game.sessions().forEach((uuid, session) -> gameByPlayer.remove(uuid, gameId));
        }
    }

    public Optional<Game> byId(String gameId) {
        return Optional.ofNullable(gamesById.get(gameId));
    }

    /** Ownership resolution for listeners: the game a player currently belongs to. */
    public Optional<Game> byPlayer(UUID uuid) {
        return Optional.ofNullable(gameByPlayer.get(uuid)).map(gamesById::get);
    }

    /** Must be called when a player joins a game so {@link #byPlayer} stays accurate. */
    public void trackPlayer(UUID uuid, String gameId) {
        gameByPlayer.put(uuid, gameId);
    }

    public void untrackPlayer(UUID uuid) {
        gameByPlayer.remove(uuid);
    }

    public Collection<Game> all() {
        return gamesById.values();
    }

    public int activeCount() {
        return gamesById.size();
    }
}