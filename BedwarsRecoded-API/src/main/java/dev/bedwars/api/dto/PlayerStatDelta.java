package dev.bedwars.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * The delta a single player earned during one match. Persistence applies these
 * as {@code UPDATE ... SET col = col + ?} so concurrent games on different pods
 * cannot clobber each other's counters.
 */
public record PlayerStatDelta(
        UUID uuid,
        String username,
        int kills,
        int finalKills,
        int deaths,
        int finalDeaths,
        int bedsBroken,
        int bedsLost,
        boolean winner,
        long experienceGained
) {
    public PlayerStatDelta {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
    }
}