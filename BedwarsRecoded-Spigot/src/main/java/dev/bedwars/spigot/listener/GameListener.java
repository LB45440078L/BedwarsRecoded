package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameManager;
import dev.bedwars.spigot.game.JoinService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Optional;

/**
 * Thin listener: every handler resolves the owning {@link Game} first, then delegates.
 * No gameplay state lives here — that is the whole point of the manager-centric design.
 *
 * <p>On join, an unassigned player is placed into a match automatically (a dedicated
 * game server's players arrive already routed by Velocity, so connecting <em>is</em>
 * joining). With several matches per server the roomiest one is chosen.
 */
public final class GameListener implements Listener {

    private final GameManager gameManager;
    private final JoinService joinService;

    public GameListener(GameManager gameManager, JoinService joinService) {
        this.gameManager = gameManager;
        this.joinService = joinService;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        if (gameManager.byPlayer(event.getPlayer().getUniqueId()).isPresent()) {
            return;
        }
        if (joinService.join(event.getPlayer()).isEmpty()) {
            event.getPlayer().sendMessage(org.bukkit.ChatColor.RED
                    + "This server is full right now - please try again shortly.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        gameManager.byPlayer(event.getPlayer().getUniqueId())
                .ifPresent(game -> game.removePlayer(event.getPlayer().getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        gameManager.byPlayer(event.getEntity().getUniqueId())
                .ifPresent(game -> game.onDeath(event.getEntity().getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        gameManager.byPlayer(event.getPlayer().getUniqueId())
                .ifPresent(game -> game.onRespawn(event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!isBed(block.getType())) {
            return;
        }
        gameManager.byPlayer(event.getPlayer().getUniqueId()).ifPresent(game -> {
            Vec3 point = toVec(block.getLocation());
            findTeamByBed(game, point).ifPresent(team ->
                    game.destroyBed(team.id(), event.getPlayer().getUniqueId(), System.currentTimeMillis()));
        });
    }

    private static boolean isBed(Material material) {
        String name = material.name();
        return name.equals("BED") || name.endsWith("_BED");
    }

    private static Optional<Team> findTeamByBed(Game game, Vec3 point) {
        return game.teams().stream()
                .filter(team -> team.bed().position().distance(point) <= 2.0)
                .findFirst();
    }

    private static Vec3 toVec(Location location) {
        return new Vec3(location.getX(), location.getY(), location.getZ());
    }
}
