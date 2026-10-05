package dev.bedwars.spigot.game;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameHost;
import dev.bedwars.spigot.world.GameWorldService;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Optional;

/**
 * The one place a player becomes part of a match on this server: find (or create) a
 * match with room, seat the player at their team spawn in that match's world, and give
 * clear feedback. Every join path — the join command, the sign, the NPC, the GUI and a
 * player simply connecting — funnels through here so the behaviour is identical.
 */
public final class JoinService {

    private final GameHost host;
    private final GameWorldService worlds;

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

    /** Removes the player from whatever match (and world) they were in. */
    public void leave(Player player) {
        for (Game game : host.games()) {
            if (game.session(player.getUniqueId()).isPresent()) {
                game.removePlayer(player.getUniqueId(), System.currentTimeMillis());
            }
        }
        if (worlds.perGameWorlds()) {
            World lobby = worlds.worldFor("lobby");
            if (lobby != null) {
                player.teleport(lobby.getSpawnLocation());
            }
        }
    }

    private void seat(Player player, Game game) {
        World world = worlds.worldFor(game.id());
        if (world == null) {
            player.sendMessage(ChatColor.RED + "Could not prepare an arena for you right now.");
            return;
        }
        Vec3 spawn = game.teamOf(player.getUniqueId())
                .flatMap(this::spawnFor)
                .orElse(new Vec3(0.5, 65, 0.5));
        player.teleport(new Location(world, spawn.x(), spawn.y(), spawn.z()));
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.sendMessage(ChatColor.GREEN + "You joined " + ChatColor.AQUA + game.id()
                + ChatColor.GREEN + ". Good luck!");
    }

    private Optional<Vec3> spawnFor(String teamId) {
        return host.arena().spawnOf(teamId).or(() -> host.arena().bedOf(teamId));
    }
}
