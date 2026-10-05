package dev.bedwars.spigot.template;

import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Development-only fallback: copies a plain world directory instead of loading a
 * Slime archive. This intentionally exists so a developer can run a pod without
 * AdvancedSlimePaper installed.
 *
 * <p><b>Not production.</b> In production the world always comes from a Slime
 * template in S3, because a directory copy cannot be instant and would mean arenas
 * on disk (hard constraint #1).
 */
public final class FallbackWorldProvider implements SlimeWorldProvider {

    private final Path sourceWorldDir;
    private final Logger log;

    public FallbackWorldProvider(Path sourceWorldDir, Logger log) {
        this.sourceWorldDir = sourceWorldDir;
        this.log = log;
    }

    @Override
    public String backend() {
        return "FALLBACK(local-copy, non-production)";
    }

    @Override
    public boolean available() {
        return sourceWorldDir != null && java.nio.file.Files.isDirectory(sourceWorldDir);
    }

    @Override
    public boolean load(Path stagedArchive, String templateName, String instanceName) throws Exception {
        log.warn("using_non_production_world_loader backend={} source={}", backend(), sourceWorldDir);
        return available();
    }
}