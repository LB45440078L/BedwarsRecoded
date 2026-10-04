package dev.bedwars.spigot.template;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.TemplateSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Dev-only fallback that copies a world directory already present on the host.
 * Clearly marked non-production: it does not scale, does not version, and keeps
 * arenas on disk (which the production model forbids). Use {@link S3TemplateSource}
 * in any real deployment.
 */
public final class LocalTemplateSource implements TemplateSource {

    private final Path root;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalTemplateSource(Path root) {
        this.root = root;
    }

    @Override
    public CompletableFuture<Path> materialise(TemplateDescriptor descriptor, Path stagingDir) {
        return CompletableFuture.supplyAsync(() -> copy(descriptor, stagingDir), executor);
    }

    private Path copy(TemplateDescriptor descriptor, Path stagingDir) {
        Path source = root.resolve(descriptor.name());
        if (!Files.isDirectory(source)) {
            throw new IllegalStateException("Local template not found: " + source);
        }
        try {
            Path target = stagingDir.resolve(descriptor.name() + "-" + descriptor.version());
            copyRecursively(source, target);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy local template " + source, e);
        }
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Override
    public boolean productionReady() {
        return false;
    }
}