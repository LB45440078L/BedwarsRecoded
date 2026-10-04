package dev.bedwars.api.service;

import java.util.UUID;

/** One row of a cached leaderboard. */
public record LeaderboardEntry(UUID uuid, String username, long value, int rank) {
}