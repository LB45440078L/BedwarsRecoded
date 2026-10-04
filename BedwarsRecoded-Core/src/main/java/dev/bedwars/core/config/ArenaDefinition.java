package dev.bedwars.core.config;

import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.shop.Shop;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything needed to instantiate one match on one template: the arena group
 * settings, per-team bed and spawn coordinates, generators and the shop.
 */
public record ArenaDefinition(
        ArenaGroup group,
        Map<String, Vec3> teamBeds,
        Map<String, Vec3> teamSpawns,
        List<GeneratorSpec> generators,
        Shop shop
) {
    public ArenaDefinition {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(shop, "shop");
        teamBeds = Map.copyOf(teamBeds);
        teamSpawns = Map.copyOf(teamSpawns);
        generators = List.copyOf(generators);
    }

    public Optional<Vec3> bedOf(String teamId) {
        return Optional.ofNullable(teamBeds.get(teamId));
    }

    public Optional<Vec3> spawnOf(String teamId) {
        return Optional.ofNullable(teamSpawns.get(teamId));
    }
}