package dev.bedwars.spigot.lobby;

import dev.bedwars.spigot.proxy.ProxyChannel;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lobby's side of matchmaking.
 *
 * <p>Deliberately thin: the lobby does not talk to the controller and does not decide
 * anything. It forwards a "this player wants a match" request to the proxy, which owns
 * the controller connection and — crucially — is the only component that can actually
 * move a player onto a game pod.
 *
 * <p>Keeping the decision in one place means there is exactly one implementation of
 * "ask the controller, then transfer", rather than a copy in every backend server.
 */
public final class LobbyQueueService {

    private final Plugin plugin;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();

    public LobbyQueueService(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Sends a player into the matchmaking queue.
     *
     * @return true when the request was handed to the proxy
     */
    public boolean queue(Player player) {
        UUID id = player.getUniqueId();
        if (!pending.add(id)) {
            player.sendMessage(ChatColor.YELLOW + "You are already searching for a match...");
            return false;
        }
        if (!ProxyChannel.requestQueue(plugin, player)) {
            pending.remove(id);
            player.sendMessage(ChatColor.RED + "Matchmaking is unavailable: this lobby is not connected"
                    + " to a proxy. Connect through the network address instead.");
            return false;
        }
        player.sendMessage(ChatColor.YELLOW + "Searching for a BedWars match" + ChatColor.GRAY
                + " - you will be moved automatically.");
        return true;
    }

    /** Leaves the queue: forgets the request locally and tells the proxy to stop searching. */
    public void cancel(Player player) {
        if (pending.remove(player.getUniqueId())) {
            ProxyChannel.requestLeave(plugin, player);
        }
    }

    /** Forgets a player's in-flight request (they disconnected, or were moved). */
    public void forget(UUID id) {
        pending.remove(id);
    }

    public boolean isSearching(UUID id) {
        return pending.contains(id);
    }
}
