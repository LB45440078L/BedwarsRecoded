package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.manager.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * NPC join: right-clicking an entity whose custom name is {@code [bedwars]} joins
 * the match. Works with any named entity (e.g. a Villager), so no external NPC
 * plugin is required; a Citizens hook can replace this by registering the same
 * name on its NPCs.
 */
public final class NpcJoinListener implements Listener {

    private final GameManager gameManager;
    private final Game game;

    public NpcJoinListener(GameManager gameManager, Game game) {
        this.gameManager = gameManager;
        this.game = game;
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        String name = event.getRightClicked().getCustomName();
        if (name == null || !ChatColor.stripColor(name).equalsIgnoreCase("[bedwars]")) {
            return;
        }
        Player player = event.getPlayer();
        if (game.session(player.getUniqueId()).isPresent()) {
            return;
        }
        try {
            game.addPlayer(player.getUniqueId(), player.getName());
            gameManager.trackPlayer(player.getUniqueId(), game.id());
            player.sendMessage(ChatColor.GREEN + "Joined the match.");
        } catch (IllegalStateException e) {
            player.sendMessage(ChatColor.RED + "Cannot join right now: " + e.getMessage());
        }
    }
}