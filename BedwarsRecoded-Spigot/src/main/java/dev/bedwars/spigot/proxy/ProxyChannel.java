package dev.bedwars.spigot.proxy;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;

/**
 * The plugin-message channels this plugin uses to talk to the Velocity proxy.
 *
 * <p>A backend server cannot move a player between servers — only the proxy can. So
 * when the lobby decides a player should enter a match, or a game server finishes a
 * match and its players should return to the lobby, the backend sends a short message
 * on these channels and Velocity does the moving.
 *
 * <p>Channel names are namespaced and lowercase, which is what both Spigot and Velocity
 * require. The payload is UTF-8 text so the contract stays readable in a packet log:
 *
 * <ul>
 *   <li>{@link #CHANNEL_QUEUE} — {@code <playerUUID>}: "put this player into a match".</li>
 *   <li>{@link #CHANNEL_RETURN} — {@code <playerUUID>} or empty: "send these players back
 *       to the lobby" (empty means everyone on the server that sent it).</li>
 * </ul>
 *
 * <p>A plugin message can only be sent through a connected player, because that is the
 * connection it rides on. Helpers here take a "carrier" player for that reason; picking
 * any online player is correct, since the proxy only trusts the <em>sending server</em>.
 */
public final class ProxyChannel {

    public static final String CHANNEL_QUEUE = "bedwars:queue";
    public static final String CHANNEL_RETURN = "bedwars:return";

    private ProxyChannel() {
    }

    /** Registers the outgoing channels, once, at plugin enable. */
    public static void register(Plugin plugin) {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_QUEUE);
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL_RETURN);
    }

    /**
     * Asks the proxy to put {@code player} into a BedWars match.
     *
     * @return true when the request left the server; false when there is no proxy to
     *         talk to (a standalone server has no Velocity, and the message would be
     *         silently dropped, so the caller can tell the player instead)
     */
    public static boolean requestQueue(Plugin plugin, Player player) {
        return send(plugin, player, CHANNEL_QUEUE, player.getUniqueId().toString());
    }

    /**
     * Asks the proxy to return {@code carrier} (and, with an empty payload, everyone on
     * this server) to the lobby.
     */
    public static boolean requestReturn(Plugin plugin, Player carrier, String payload) {
        return send(plugin, carrier, CHANNEL_RETURN, payload == null ? "" : payload);
    }

    private static boolean send(Plugin plugin, Player carrier, String channel, String payload) {
        if (carrier == null || !carrier.isOnline()) {
            return false;
        }
        if (!plugin.getServer().getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
            return false;
        }
        try {
            carrier.sendPluginMessage(plugin, channel, payload.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException e) {
            carrier.sendMessage(ChatColor.RED + "Could not reach the network proxy; try again in a moment.");
            return false;
        }
    }
}
