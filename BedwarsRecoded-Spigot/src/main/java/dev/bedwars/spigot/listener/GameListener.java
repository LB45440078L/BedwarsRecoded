package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameManager;
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
 * Thin listener: every handler resolves the owning {@link Game} first, then
 * delegates. No gameplay state lives here — that is the whole point of the
 * manager-centric design.
 */
public final class GameListener implements Listener {

    private final GameManager gameManager;

    public GameListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        gameManager.byPlayer(event.getPlayer().getUniqueId()).ifPresent(game -> {
            if (game.session(event.getPlayer().getUniqueId()).isEmpty()) {
                game.addPlayer(event.getPlayer().getUniqueId(), event.getPlayer().getName());
                gameManager.trackPlayer(event.getPlayer().getUniqueId(), game.id());
            }
        });
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