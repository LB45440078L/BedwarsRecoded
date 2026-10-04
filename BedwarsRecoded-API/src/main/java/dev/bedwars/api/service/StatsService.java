package dev.bedwars.api.service;

import dev.bedwars.api.dto.PlayerStatDelta;
import dev.bedwars.api.dto.PlayerStats;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * All player-stat persistence. Every method that touches the database returns a
 * {@link CompletableFuture} and must never be invoked on the server tick thread.
 * Writes are additive deltas keyed by UUID so multiple pods/proxies can write
 * concurrently without last-write-wins corruption.
 */
public interface StatsService {

    /** Loads a player's stats, creating a fresh row if absent. */
    CompletableFuture<PlayerStats> load(UUID uuid, String username);

    /** Applies an additive delta atomically ({@code SET col = col + ?}). */
    CompletableFuture<Void> apply(PlayerStatDelta delta);

    /** Persists a player's ELO after a match. */
    CompletableFuture<Void> updateElo(UUID uuid, int newElo);
}