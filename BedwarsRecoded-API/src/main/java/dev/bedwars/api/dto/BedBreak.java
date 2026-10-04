package dev.bedwars.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * A single bed destruction, timestamped relative to game start (millis).
 * {@code breaker} is {@code null} when the bed was destroyed by the system
 * (sudden death) rather than a player.
 */
public record BedBreak(String teamId, UUID breaker, long atMillis) {
    public BedBreak {
        Objects.requireNonNull(teamId, "teamId");
    }

    public boolean systemBreak() {
        return breaker == null;
    }
}