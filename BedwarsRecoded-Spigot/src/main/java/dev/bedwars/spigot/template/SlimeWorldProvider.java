package dev.bedwars.spigot.template;

import java.nio.file.Path;

/**
 * Loads a staged Slime template as the live world for this pod.
 *
 * <p>Implementations differ by runtime: {@link AspSlimeWorldProvider} uses the
 * AdvancedSlimePaper API (production), {@link FallbackWorldProvider} copies a
 * directory (development only).
 */
public interface SlimeWorldProvider {

    /** Human-readable backend name, for logs and the ready report. */
    String backend();

    /** True when this provider can actually load a world on the current server. */
    boolean available();

    /**
     * Loads {@code templateName} as the pod's active world.
     *
     * @param stagedArchive the {@code .slime} archive already staged on local disk
     * @param instanceName  the per-match world name (a clone; the template is never mutated)
     * @return true when the world is loaded and playable
     */
    boolean load(Path stagedArchive, String templateName, String instanceName) throws Exception;
}