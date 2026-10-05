package dev.bedwars.spigot.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Gives each match its own world when a server hosts more than one match.
 *
 * <p>With {@code games-per-server: 1} (the default) every match uses the server's
 * existing world, which is exactly the old single-match behaviour. With a higher value
 * each match gets a private copy of the template world directory ({@code bw-<gameId>}),
 * loaded on demand and deleted when the match is reclaimed — two matches therefore never
 * share arena coordinates.
 *
 * <p>World creation/unload must happen on the server thread; this class is called only
 * from the plugin's main-thread paths.
 */
public final class GameWorldService {

    private final Plugin plugin;
    private final Path templateDir;
    private final boolean perGameWorlds;
    private final Logger log;
    private final Map<String, World> worlds = new HashMap<>();

    public GameWorldService(Plugin plugin, Path templateDir, boolean perGameWorlds, Logger log) {
        this.plugin = plugin;
        this.templateDir = templateDir;
        this.perGameWorlds = perGameWorlds;
        this.log = log;
    }

    public boolean perGameWorlds() {
        return perGameWorlds;
    }

    /**
     * The world a match should run in. In single-match mode this is the server's main
     * world; otherwise a private world is created (once) from the template directory.
     */
    public World worldFor(String gameId) {
        if (!perGameWorlds) {
            return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        }
        String name = worldName(gameId);
        World cached = worlds.get(name);
        if (cached != null) {
            return cached;
        }
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            worlds.put(name, loaded);
            return loaded;
        }

        Path destination = Bukkit.getWorldContainer().toPath().resolve(name);
        if (Files.isDirectory(templateDir)) {
            try {
                copyDirectory(templateDir, destination);
            } catch (IOException e) {
                log.error("Failed to stage match world {} from {}: {}", name, templateDir, e.toString());
                return null;
            }
        } else {
            log.warn("No template world at {}; match {} will start in a generated world", templateDir, gameId);
        }

        World created = new WorldCreator(name).createWorld();
        if (created != null) {
            worlds.put(name, created);
        } else {
            log.error("World creation returned null for {}", name);
        }
        return created;
    }

    /** Unloads and deletes a match's private world. A no-op in single-match mode. */
    public void release(String gameId) {
        if (!perGameWorlds) {
            return;
        }
        String name = worldName(gameId);
        World world = worlds.remove(gameId);
        World target = world != null ? world : Bukkit.getWorld(name);
        if (target != null) {
            Bukkit.unloadWorld(target, false);
        }
        deleteDirectory(Bukkit.getWorldContainer().toPath().resolve(name));
    }

    private static String worldName(String gameId) {
        return "bw-" + gameId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteDirectory(target);
        }
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (!isTransientWorldFile(relative.toString())) {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Session locks and the like must not be copied into a fresh world. */
    private static boolean isTransientWorldFile(String relative) {
        String name = relative.replace('\\', '/');
        return name.equals("session.lock") || name.endsWith("/session.lock");
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up world directory {}: {}", directory, e.getMessage());
        }
    }
}
