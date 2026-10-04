package dev.bedwars.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration for an arena group (solo / doubles / 4s ...). Loaded
 * per-group from YAML; a pod hosts exactly one group.
 *
 * @param voidYThreshold        below this Y a falling player is killed by the void
 * @param islandRadius          build/break allowed within this radius of an island
 * @param bedProtectionRadius   block placement forbidden within this radius of a bed
 * @param suddenDeathAfterSecs  seconds of RUNNING before sudden death; 0 disables
 */
public record ArenaGroup(
        String id,
        int teamCount,
        int playersPerTeam,
        int countdownSeconds,
        int suddenDeathAfterSecs,
        double voidYThreshold,
        double islandRadius,
        double bedProtectionRadius,
        List<GeneratorType> teamGenerators
) {
    public ArenaGroup {
        Objects.requireNonNull(id, "id");
        teamGenerators = List.copyOf(teamGenerators);
        if (teamCount < 1 || playersPerTeam < 1) {
            throw new IllegalArgumentException("teamCount and playersPerTeam must be positive");
        }
    }

    public int capacity() {
        return teamCount * playersPerTeam;
    }
}