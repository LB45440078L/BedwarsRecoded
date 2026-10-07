package dev.bedwars.spigot.lobby;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.weather.WeatherChangeEvent;

/**
 * Makes the lobby behave like a lobby rather than like a match host.
 *
 * <p>A player waiting for matchmaking should not be able to build, take fall damage,
 * starve, or be attacked. More practically: on a server whose main world is an arena
 * map, an unprotected lobby is a griefed arena within minutes.
 *
 * <p>Every rule is scoped to the lobby world, so a server that also hosts matches keeps
 * normal gameplay where it belongs.
 */
public final class LobbyProtectionListener implements Listener {

    private final String lobbyWorldName;

    public LobbyProtectionListener(String lobbyWorldName) {
        this.lobbyWorldName = lobbyWorldName;
    }

    private boolean inLobby(World world) {
        return world != null && world.getName().equalsIgnoreCase(lobbyWorldName);
    }

    /** New arrivals are placed at the lobby spawn, on foot and empty-handed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (!inLobby(world)) {
            return;
        }
        Location spawn = world.getSpawnLocation();
        if (player.getLocation().distanceSquared(spawn) > 4.0) {
            player.teleport(spawn);
        }
        if (player.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameMode.ADVENTURE);
        }
        player.setFoodLevel(20);
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        player.sendMessage(ChatColor.AQUA + "Welcome! " + ChatColor.GRAY
                + "Right-click a BedWars sign or NPC to join a match.");
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (inLobby(event.getBlock().getWorld()) && !mayBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent event) {
        if (inLobby(event.getBlock().getWorld()) && !mayBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (inLobby(event.getPlayer().getWorld()) && !mayBuild(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Staff keep normal build rights in the hub.
     *
     * <p>Without this the hub could never be built: the protection rules apply to everyone,
     * so placing the queue signs — the very thing a lobby exists to hold — would be
     * impossible while the server is running. Grant {@code bedwars.lobby.build} (operators
     * have it by default) to anyone who maintains the hub.
     */
    private static boolean mayBuild(Player player) {
        return player.hasPermission("bedwars.lobby.build");
    }

    /** No fall damage, no drowning, no fire — a lobby must never kill anyone. */
    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (inLobby(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent event) {
        if (inLobby(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && inLobby(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    /**
     * No ambient mobs in the hub: no night-time ambushes, no lag.
     *
     * <p>Deliberate spawns are allowed through. An admin placing a queue NPC with a
     * spawn egg, {@code /summon}, or a plugin (Citizens) must not have it deleted the
     * instant it appears — that would break NPC matchmaking, which is the main way a
     * Hypixel-style lobby is used.
     */
    @EventHandler
    public void onSpawn(CreatureSpawnEvent event) {
        if (!inLobby(event.getLocation().getWorld())) {
            return;
        }
        String reason = event.getSpawnReason().name();
        boolean deliberate = reason.contains("CUSTOM") || reason.contains("PLUGIN")
                || reason.contains("COMMAND") || reason.contains("EGG")
                || reason.contains("DISPENSE") || reason.startsWith("BUILD_")
                || reason.equals("BREEDING") || reason.equals("CURED");
        if (!deliberate) {
            event.setCancelled(true);
        }
    }
}
