package dev.bedwars.spigot.lobby;

import org.bukkit.ChatColor;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;

/**
 * The lobby's queue triggers: signs and NPCs.
 *
 * <p>This is the "Hypixel-style" entry point — a player never types a command to play;
 * they walk up to a sign or a character and click it.
 *
 * <p>Signs are read from their first line, which is the convention every server network
 * uses because it needs no configuration:
 *
 * <pre>
 *   [bedwars]      <- right-click to queue
 *   Solo
 *   Click to play
 * </pre>
 *
 * <p>NPCs are any entity whose <em>custom name</em> is {@code [bedwars]} — the same
 * convention the game server's own NPC listener uses, so the two roles behave alike.
 * That deliberately avoids a hard dependency on an NPC plugin: name a villager, an
 * armour stand, or a zombie and it becomes a queue NPC. (A real network would use
 * Citizens/ModelEngine for the skin; the trigger contract is the same.)
 */
public final class LobbyQueueListener implements Listener {

    /** Sign headers that start matchmaking. Case-insensitive, colour codes stripped. */
    private static final Set<String> SIGN_HEADERS = Set.of("[bedwars]", "[bw]", "[bwqueue]");

    /** Custom entity names that act as a queue NPC. */
    private static final Set<String> NPC_NAMES = Set.of("[bedwars]", "[bw]", "[bwqueue]");

    private final LobbyQueueService queue;

    public LobbyQueueListener(LobbyQueueService queue) {
        this.queue = queue;
    }

    @EventHandler
    public void onSignInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof Sign sign)) {
            return;
        }
        String header = ChatColor.stripColor(sign.getLine(0));
        if (header == null || !SIGN_HEADERS.contains(header.trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        event.setCancelled(true);
        queue.queue(event.getPlayer());
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        String name = event.getRightClicked().getCustomName();
        if (name == null) {
            return;
        }
        if (!NPC_NAMES.contains(ChatColor.stripColor(name).trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        event.setCancelled(true);
        queue.queue(event.getPlayer());
    }

    /** A player who leaves must not keep their in-flight request, or they can never requeue. */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queue.forget(event.getPlayer().getUniqueId());
    }
}
