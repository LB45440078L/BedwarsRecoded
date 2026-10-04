package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Enforces the bed protection radius: no block may be placed within the radius of
 * a standing bed. Also keeps players from building outside their island radius.
 */
public final class ProtectionListener implements Listener {

    private final GameManager gameManager;

    public ProtectionListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Game game = gameManager.byPlayer(event.getPlayer().getUniqueId()).orElse(null);
        if (game == null) {
            return;
        }
        Vec3 point = new Vec3(event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
        if (game.isProtectedFromBuild(point)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot place blocks that close to a bed.");
            return;
        }
        String teamId = game.teamOf(event.getPlayer().getUniqueId()).orElse(null);
        if (teamId != null && !game.isWithinIsland(point, teamId)) {
            // Building is only allowed within an island radius.
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "You cannot build outside the island.");
        }
    }
}