package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.PlayerState;
import dev.bedwars.core.manager.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.Plugin;

/** Puts eliminated players into spectator mode on the next tick. */
public final class SpectatorListener implements Listener {

    private final GameManager gameManager;
    private final Plugin plugin;

    public SpectatorListener(GameManager gameManager, Plugin plugin) {
        this.gameManager = gameManager;
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Game game = gameManager.byPlayer(player.getUniqueId()).orElse(null);
        if (game == null) {
            return;
        }
        game.session(player.getUniqueId()).ifPresent(session -> {
            if (session.state() == PlayerState.ELIMINATED) {
                // Defer one tick: the death screen owns the player this tick.
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.sendMessage(ChatColor.GRAY + "You have been eliminated. You are now a spectator.");
                });
            }
        });
    }
}