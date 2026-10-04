package dev.bedwars.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, platform-neutral snapshot of a player's persisted statistics.
 * This is the single source of truth shape for the {@code player_stats} table;
 * nothing in the game pod keeps a competing local copy (see constraint #4).
 */
public record PlayerStats(
        UUID uuid,
        String username,
        int kills,
        int deaths,
        int finalKills,
        int finalDeaths,
        int wins,
        int losses,
        int bedsBroken,
        int bedsLost,
        int gamesPlayed,
        long experience,
        int elo
) {
    public PlayerStats {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(username, "username");
    }

    /** A brand-new player with zeroed counters and the configured baseline ELO. */
    public static PlayerStats fresh(UUID uuid, String username, int baselineElo) {
        return new PlayerStats(uuid, username, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, baselineElo);
    }

    public double kdr() {
        return deaths == 0 ? kills : (double) kills / deaths;
    }
}