package dev.bedwars.api.service;

import dev.bedwars.api.dto.TemplateDescriptor;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a Slime map template to a local directory the server can load.
 * Production implementation pulls from S3; the local implementation is the
 * documented dev-only fallback.
 */
public interface TemplateSource {

    /**
     * Materialises {@code descriptor} under {@code stagingDir} and completes with
     * the loadable directory. Must run off the main thread.
     */
    CompletableFuture<Path> materialise(TemplateDescriptor descriptor, Path stagingDir);

    /** Whether this source is safe for production (S3) or dev-only (LOCAL). */
    boolean productionReady();
}