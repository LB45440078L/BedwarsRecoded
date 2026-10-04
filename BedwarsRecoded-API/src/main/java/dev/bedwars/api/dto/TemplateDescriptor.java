package dev.bedwars.api.dto;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies exactly which map template a pod loaded. The {@code version} is a
 * semantic version so the controller can canary a new map build by scaling a
 * GameServerSet that requests a different version.
 */
public record TemplateDescriptor(String name, String version, TemplateSource source, Optional<String> checksum) {
    public TemplateDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(source, "source");
        // Gson's reflective adapter yields a Java null for JSON null, bypassing the
        // Optional adapter; normalise so an absent checksum is always Optional.empty().
        if (checksum == null) {
            checksum = Optional.empty();
        }
    }

    /** Convenience: {@code Glacier@1.4.0}. */
    public String coordinate() {
        return name + "@" + version;
    }
}