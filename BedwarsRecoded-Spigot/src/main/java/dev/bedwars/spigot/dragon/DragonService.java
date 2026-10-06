package dev.bedwars.spigot.dragon;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.spigot.config.PluginConfig.DragonConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sudden-death dragon: a real Ender Dragon that tears up the map and hurls players
 * away with a strong knockback.
 *
 * <p>Two kinds of dragon exist. A <em>neutral</em> dragon (configured by
 * {@code dragon.base-per-match}) attacks every team. A <em>team</em> dragon comes from
 * that team's Dragon Buff — one per level — and attacks only the other teams, so buying
 * the buff is a real, visible advantage. The spawn plan itself is computed in Core
 * ({@link Game#dragonsByTeam()}); this class only realises it.
 *
 * <p>Everything runs on the server thread. Block destruction is bounded (a small radius,
 * a few blocks per column, on an interval) so a dragon cannot stall a tick, and it skips
 * the same blocks vanilla dragons cannot break so the arena floor and the void stay put.
 */
public final class DragonService {

    /** One live dragon: its entity id, and the team it fights for (null = neutral). */
    private record Active(UUID entityId, String ownerTeamId) {
    }

    private volatile DragonConfig settings;
    private final Logger log;
    private final Map<String, List<Active>> byGame = new HashMap<>();
    private final Map<String, Integer> blocksDestroyed = new HashMap<>();
    private final Set<String> loggedFirstKnockback = new HashSet<>();
    private final Set<String> loggedFirstDamage = new HashSet<>();
    private long ticks;

    public DragonService(DragonConfig settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    public boolean enabled() {
        return settings.enabled();
    }

    /** Applies new settings after a config reload; live dragons keep flying. */
    public void updateSettings(DragonConfig fresh) {
        this.settings = fresh;
    }

    public boolean hasDragons(String gameId) {
        return byGame.containsKey(gameId);
    }

    public int dragonCount() {
        return byGame.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Spawns a match's sudden-death dragons in its world. Idempotent per game: calling it
     * twice for the same match does not double the dragons.
     *
     * @return how many dragons now exist for this match
     */
    public int spawnForGame(Game game, World world) {
        if (!settings.enabled() || world == null) {
            return 0;
        }
        List<Active> existing = byGame.get(game.id());
        if (existing != null && !existing.isEmpty()) {
            return existing.size();
        }
        List<Active> spawned = new ArrayList<>();
        for (int i = 0; i < settings.basePerMatch(); i++) {
            spawn(game, world, null, i, spawned);
        }
        game.dragonsByTeam().forEach((teamId, count) -> {
            for (int i = 0; i < count; i++) {
                spawn(game, world, teamId, i, spawned);
            }
        });
        if (!spawned.isEmpty()) {
            byGame.put(game.id(), spawned);
            log.info("dragons_spawned game={} total={} team_dragons={}",
                    game.id(), spawned.size(), game.dragonsByTeam());
        }
        return spawned.size();
    }

    private void spawn(Game game, World world, String ownerTeamId, int index, List<Active> into) {
        Location origin = originFor(game, world, ownerTeamId, index);
        try {
            EnderDragon dragon = world.spawn(origin, EnderDragon.class);
            dragon.setCustomName(ChatColor.DARK_PURPLE
                    + (ownerTeamId == null ? "Bedwars Dragon" : ownerTeamId + " Dragon"));
            dragon.setCustomNameVisible(true);
            dragon.setPhase(EnderDragon.Phase.CIRCLING);
            dragon.setMaxHealth(settings.health());
            dragon.setHealth(settings.health());
            into.add(new Active(dragon.getUniqueId(), ownerTeamId));
            world.playSound(origin, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 1.0f);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, origin, 1);
        } catch (RuntimeException e) {
            // A dragon that will not spawn must never take the match down with it.
            log.warn("dragon_spawn_failed game={} reason={}", game.id(), e.toString());
        }
    }

    /**
     * Above the map centre for a neutral dragon, above the owner team's island for a team
     * dragon, so each is visible from the fights it cares about.
     */
    private Location originFor(Game game, World world, String ownerTeamId, int index) {
        Vec3 anchor = game.team(ownerTeamId == null ? "" : ownerTeamId)
                .map(team -> team.bed().position())
                .orElseGet(() -> centreOf(game));
        double x = (anchor == null ? 0.0 : anchor.x()) + 0.5;
        double z = (anchor == null ? 0.0 : anchor.z()) + 0.5;
        double y = (anchor == null ? 64.0 : anchor.y()) + settings.spawnHeight() + index * 6.0;
        return new Location(world, x, y, z);
    }

    private static Vec3 centreOf(Game game) {
        List<Vec3> beds = game.teams().stream().map(team -> team.bed().position()).toList();
        if (beds.isEmpty()) {
            return null;
        }
        double x = beds.stream().mapToDouble(Vec3::x).average().orElse(0.0);
        double z = beds.stream().mapToDouble(Vec3::z).average().orElse(0.0);
        double y = beds.stream().mapToDouble(Vec3::y).average().orElse(64.0);
        return new Vec3(x, y, z);
    }

    /**
     * One server tick's worth of dragon behaviour: map destruction and knockback, each on
     * its own interval. Called every tick from the plugin's main loop.
     */
    public void tick(Iterable<Game> games) {
        if (!settings.enabled() || byGame.isEmpty()) {
            return;
        }
        ticks++;
        boolean knockbackNow = ticks % settings.knockbackIntervalTicks() == 0;
        boolean destroyNow = settings.destroyRadius() > 0 && ticks % settings.destroyIntervalTicks() == 0;
        for (Game game : games) {
            List<Active> list = byGame.get(game.id());
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (Active active : list) {
                Entity entity = Bukkit.getEntity(active.entityId());
                if (!(entity instanceof EnderDragon dragon) || dragon.isDead()) {
                    continue;
                }
                if (destroyNow) {
                    int broke = destroyAround(dragon.getLocation());
                    if (broke > 0) {
                        int total = blocksDestroyed.merge(game.id(), broke, Integer::sum);
                        if (loggedFirstDamage.add(game.id())) {
                            log.info("dragon_map_damage game={} blocks_first_pass={} total={}",
                                    game.id(), broke, total);
                        }
                    }
                }
                if (knockbackNow) {
                    steer(dragon, game, active.ownerTeamId());
                    knockback(game, dragon, active.ownerTeamId());
                }
            }
        }
    }

    /**
     * Keeps the dragon in the fight: it targets the nearest enemy player and dives at them
     * when they are beyond its knockback reach. Without this a dragon drifts off to circle
     * the world origin and stops mattering.
     */
    private void steer(EnderDragon dragon, Game game, String ownerTeamId) {
        Player nearest = nearestEnemy(dragon, game, ownerTeamId);
        if (nearest == null) {
            return;
        }
        dragon.setTarget(nearest);
        if (Math.sqrt(dragon.getLocation().distanceSquared(nearest.getLocation()))
                > settings.knockbackRadius()) {
            dragon.setPhase(EnderDragon.Phase.CHARGE_PLAYER);
        }
    }

    private static Player nearestEnemy(EnderDragon dragon, Game game, String ownerTeamId) {
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : dragon.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (ownerTeamId != null
                    && ownerTeamId.equals(game.teamOf(player.getUniqueId()).orElse(null))) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(dragon.getLocation());
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /** Hurls every enemy player near the dragon away from it. */
    private void knockback(Game game, EnderDragon dragon, String ownerTeamId) {
        Location dragonLocation = dragon.getLocation();
        double radiusSquared = settings.knockbackRadius() * settings.knockbackRadius();
        for (Player player : dragonLocation.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            // A team dragon spares its own team; a neutral dragon spares nobody.
            if (ownerTeamId != null
                    && ownerTeamId.equals(game.teamOf(player.getUniqueId()).orElse(null))) {
                continue;
            }
            if (player.getLocation().distanceSquared(dragonLocation) > radiusSquared) {
                continue;
            }
            Vector push = player.getLocation().toVector().subtract(dragonLocation.toVector());
            if (push.lengthSquared() < 1.0e-4) {
                push = new Vector(1, 0, 0);
            }
            push.normalize().multiply(settings.knockback()).setY(settings.knockback() * 0.7);
            player.setVelocity(push);
            if (loggedFirstKnockback.add(game.id())) {
                // Proof signal: the dragon reached a player and threw them.
                log.info("dragon_knockback game={} player={} strength={}",
                        game.id(), player.getName(), settings.knockback());
            }
            if (settings.damage() > 0.0) {
                player.damage(settings.damage());
            }
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.2f, 1.0f);
        }
    }

    /**
     * Tears blocks out of the map in a bounded column under and around the dragon. The
     * scan works downward from the dragon to the terrain beneath it, so a dragon flying
     * high still scorches the ground it passes over. Skips air, and the blocks vanilla
     * dragons cannot break (bedrock, obsidian, end stone, barriers, portal frames) plus
     * beds, so a dragon never ends a match by breaking a bed.
     */
    private int destroyAround(Location centre) {
        World world = centre.getWorld();
        int radius = settings.destroyRadius();
        int depth = settings.destroyDepth();
        int cx = centre.getBlockX();
        int cy = centre.getBlockY();
        int cz = centre.getBlockZ();
        int radiusSquared = radius * radius;
        int destroyed = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                int removed = 0;
                for (int dy = 0; dy >= -depth && removed < 3; dy--) {
                    Block block = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (isDragonBreakable(block.getType())) {
                        block.setType(Material.AIR, false);
                        world.spawnParticle(Particle.LARGE_SMOKE,
                                block.getLocation().add(0.5, 1.0, 0.5), 4, 0.3, 0.3, 0.3, 0.0);
                        removed++;
                        destroyed++;
                    }
                }
            }
        }
        if (destroyed > 0) {
            world.playSound(centre, Sound.ENTITY_DRAGON_FIREBALL_EXPLODE, 1.5f, 0.7f);
        }
        return destroyed;
    }

    private static boolean isDragonBreakable(Material type) {
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return false;
        }
        if (type == Material.BEDROCK || type == Material.BARRIER || type == Material.OBSIDIAN
                || type == Material.END_STONE || type == Material.END_PORTAL_FRAME) {
            return false;
        }
        return !type.name().endsWith("_BED");
    }

    /** Removes a match's dragons (match ended, aborted or pruned). */
    public int clearGame(String gameId) {
        List<Active> list = byGame.remove(gameId);
        if (list == null) {
            return 0;
        }
        Integer destroyed = blocksDestroyed.remove(gameId);
        loggedFirstKnockback.remove(gameId);
        loggedFirstDamage.remove(gameId);
        if (destroyed != null) {
            log.info("dragons_cleared game={} dragons={} blocks_destroyed={}",
                    gameId, list.size(), destroyed);
        }
        int removed = 0;
        for (Active active : list) {
            Entity entity = Bukkit.getEntity(active.entityId());
            if (entity != null) {
                entity.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Removes every dragon (server shutdown). */
    public void shutdown() {
        for (String gameId : new ArrayList<>(byGame.keySet())) {
            clearGame(gameId);
        }
    }
}
