package dev.bedwars.spigot.game;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GameState;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameHost;
import dev.bedwars.spigot.world.GameWorldService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The one place a player becomes part of a match on this server: find (or create) a match
 * with room, hold the player in that match's <em>waiting room</em> until it starts, and send
 * them to their island when it does. Every join path -- the join command, the sign, the NPC,
 * the GUI and a player simply connecting -- funnels through here so the behaviour is
 * identical.
 *
 * <h2>Why a waiting room, and not the team spawn</h2>
 *
 * The original plugin teleported a joining player to a per-arena waiting room
 * ({@code map-lobby-spawn}, e.g. {@code 0, 118.05, 0} for Glacier) and left them there until
 * the match began. This recode dropped that and seated players at their team spawn the
 * instant they connected, before the arena world was staged and its chunks were loaded --
 * so players landed underground, on unloaded ground, or in the void. The waiting room is
 * not decoration: it is the only place in the arena that is guaranteed to exist and be
 * loaded before the match starts.
 *
 * <p>If an arena names no waiting room, seating falls back to the world's own spawn -- a
 * real, always-loaded location -- and says so once, rather than inventing a coordinate.
 */
public final class JoinService {

    private static final Logger LOG = Logger.getLogger("bedwars-join");

    private final GameHost host;
    private final GameWorldService worlds;
    private final Set<String> warnedAboutMissingWaitingRoom = new HashSet<>();

    public JoinService(GameHost host, GameWorldService worlds) {
        this.host = host;
        this.worlds = worlds;
    }

    /**
     * Joins the player to a match.
     *
     * @return the match they are now in, or empty when the server is at capacity
     */
    public Optional<Game> join(Player player) {
        Optional<Game> joined = host.join(player.getUniqueId(), player.getName(), System.currentTimeMillis());
        joined.ifPresent(game -> seat(player, game));
        return joined;
    }

    public boolean isPlaying(Player player) {
        return host.games().stream().anyMatch(game -> game.session(player.getUniqueId()).isPresent());
    }

    /** True while the player is waiting for their match to start, not fighting in it. */
    public boolean isWaiting(Player player) {
        return host.games().stream().anyMatch(game ->
                game.session(player.getUniqueId()).isPresent()
                        && (game.state() == GameState.WAITING || game.state() == GameState.COUNTDOWN));
    }

    /** Removes the player from whatever match (and world) they were in. */
    public void leave(Player player) {
        for (Game game : host.games()) {
            if (game.session(player.getUniqueId()).isPresent()) {
                game.removePlayer(player.getUniqueId(), System.currentTimeMillis());
            }
        }
        player.setInvulnerable(false);
        if (worlds.perGameWorlds()) {
            World lobby = worlds.worldFor("lobby");
            if (lobby != null) {
                player.teleport(lobby.getSpawnLocation());
            }
        }
    }

    /**
     * Sends everyone still in the waiting room to their island. Called by the countdown the
     * moment the match begins, so a player's first sight of the arena is when it is loaded
     * and they are meant to be there.
     */
    public void sendToTeamSpawns(Game game) {
        World world = worlds.worldFor(game.id());
        if (world == null) {
            LOG.warning("Match " + game.id() + " started but its world is gone; players stay put");
            return;
        }
        for (UUID uuid : game.sessions().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            Optional<Vec3> spawn = game.teamOf(uuid).flatMap(this::spawnFor);
            if (spawn.isEmpty()) {
                // Never teleport to a guessed position: leave them where they are (the waiting
                // room) and say so, instead of dropping them somewhere arbitrary.
                LOG.warning("No spawn for " + player.getName() + " in " + game.id()
                        + " (team " + game.teamOf(uuid).orElse("none") + "); leaving them in the waiting room");
                continue;
            }
            Vec3 v = spawn.get();
            player.teleport(new Location(world, v.x(), v.y(), v.z()));
            player.setFallDistance(0f);
            player.setInvulnerable(false);
            player.setFireTicks(0);
        }
    }

    private void seat(Player player, Game game) {
        World world = worlds.worldFor(game.id());
        if (world == null) {
            player.sendMessage(ChatColor.RED + "Could not prepare an arena for you right now.");
            return;
        }
        Location waiting = waitingRoomLocation(world, game);
        player.teleport(waiting);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.setFallDistance(0f);
        // Held, not fighting: no fall, fire, hunger or mob damage while the match waits for
        // players. Restored the moment the match begins.
        player.setInvulnerable(true);
        player.setFoodLevel(20);
        player.setFireTicks(0);

        int needed = game.group().effectiveMinPlayers() - game.playerCount();
        player.sendMessage(ChatColor.GREEN + "You joined " + ChatColor.AQUA + game.id() + ChatColor.GREEN
                + ". You are in the waiting room until the match starts.");
        if (needed > 0) {
            player.sendMessage(ChatColor.YELLOW + "Waiting for " + needed + " more player(s) to start.");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Enough players - the countdown is starting.");
        }
    }

    /**
     * The location a waiting player is held at: the arena's configured waiting room, or the
     * world spawn when the arena does not name one.
     */
    private Location waitingRoomLocation(World world, Game game) {
        Optional<Vec3> configured = game.group().waitingRoom();
        if (configured.isPresent()) {
            Vec3 v = configured.get();
            Location location = new Location(world, v.x(), v.y(), v.z());
            if (game.group().waitingRoomPlatform()) {
                ensureFloor(location);
            }
            return location;
        }
        if (warnedAboutMissingWaitingRoom.add(game.group().id())) {
            LOG.warning("Arena group '" + game.group().id() + "' has no waiting room (group.lobby-spawn). "
                    + "Falling back to the world spawn; set the waiting room so players are held at "
                    + "a known place instead of wherever the world happens to spawn.");
        }
        return world.getSpawnLocation();
    }

    /**
     * Puts a small barrier floor under the waiting room when there is nothing solid there.
     *
     * <p>The waiting room is usually a built platform (Glacier's is at y=118), but a map that
     * has one configured and no floor would drop every waiting player into the void -- the
     * exact failure being fixed. Invisible barrier blocks make that impossible.
     */
    private void ensureFloor(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Block below = world.getBlockAt(location.getBlockX(),
                (int) Math.floor(location.getY()) - 1, location.getBlockZ());
        if (below.getType().isSolid()) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block block = world.getBlockAt(below.getX() + dx, below.getY(), below.getZ() + dz);
                if (!block.getType().isSolid()) {
                    block.setType(Material.BARRIER, false);
                }
            }
        }
        LOG.info("Placed a safety floor under the waiting room at " + location.getBlockX() + ","
                + (int) location.getY() + "," + location.getBlockZ()
                + " (set group.waiting-room-platform: false to manage this yourself)");
    }

    /**
     * Catches a waiting player who somehow ends up below the world (a fall they slept
     * through, a teleport from elsewhere) and puts them back in the waiting room.
     */
    public void rescueIfFallen(Player player, Game game) {
        if (game.state() != GameState.WAITING && game.state() != GameState.COUNTDOWN) {
            return;
        }
        if (player.getLocation().getY() >= game.group().voidYThreshold()) {
            return;
        }
        World world = worlds.worldFor(game.id());
        if (world == null) {
            return;
        }
        player.teleport(waitingRoomLocation(world, game));
        player.setFallDistance(0f);
    }

    private Optional<Vec3> spawnFor(String teamId) {
        return host.arena().spawnOf(teamId).or(() -> host.arena().bedOf(teamId));
    }
}
