package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.manager.GameManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/** Kills players who fall below the arena group's void-Y threshold. */
public final class VoidKillListener implements Listener {

    private final GameManager gameManager;

    public VoidKillListener(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        Game game = gameManager.byPlayer(event.getPlayer().getUniqueId()).orElse(null);
        if (game == null) {
            return;
        }
        Vec3 to = new Vec3(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());
        if (game.isVoidKill(to)) {
            game.onDeath(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }
}