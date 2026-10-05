package dev.bedwars.spigot.listener;

import dev.bedwars.spigot.game.JoinService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * NPC join: right-clicking an entity whose custom name is {@code [bedwars]} joins a
 * match on this server. Works with any named entity (e.g. a Villager), so no external
 * NPC plugin is required.
 */
public final class NpcJoinListener implements Listener {

    private final JoinService joinService;

    public NpcJoinListener(JoinService joinService) {
        this.joinService = joinService;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        String name = event.getRightClicked().getCustomName();
        if (name == null || !ChatColor.stripColor(name).equalsIgnoreCase("[bedwars]")) {
            return;
        }
        Player player = event.getPlayer();
        if (joinService.isPlaying(player)) {
            return;
        }
        if (joinService.join(player).isEmpty()) {
            player.sendMessage(ChatColor.RED + "No free match on this server right now.");
        }
    }
}
