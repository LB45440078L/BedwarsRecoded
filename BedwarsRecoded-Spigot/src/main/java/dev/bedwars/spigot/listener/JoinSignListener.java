package dev.bedwars.spigot.listener;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.manager.GameManager;
import org.bukkit.ChatColor;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Sign join: right-clicking a sign whose first line is {@code [bedwars]} adds the
 * player to this pod's match. (In production, join happens in the lobby and the
 * player is routed by Velocity; this is the in-pod join path.)
 */
public final class JoinSignListener implements Listener {

    private final GameManager gameManager;
    private final Game game;

    public JoinSignListener(GameManager gameManager, Game game) {
        this.gameManager = gameManager;
        this.game = game;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        String header = ChatColor.stripColor(sign.getLine(0));
        if (header == null || !header.equalsIgnoreCase("[bedwars]")) {
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