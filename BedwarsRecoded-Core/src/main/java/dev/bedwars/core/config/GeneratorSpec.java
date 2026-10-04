package dev.bedwars.core.config;

import dev.bedwars.core.domain.GeneratorTier;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Vec3;

import java.util.Objects;

/** A generator placed on the map template. */
public record GeneratorSpec(String id, GeneratorType type, GeneratorTier tier, Vec3 position) {

    public GeneratorSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(position, "position");
    }
}