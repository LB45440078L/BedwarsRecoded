package dev.bedwars.api.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Read-side of rankings. Implementations serve from a periodically refreshed
 * cache (never a per-request query) and expose the last refresh instant so the
 * lobby can show staleness.
 */
public interface LeaderboardService {

    /** Board identifiers, e.g. {@code wins}, {@code elo}, {@code kills}. */
    List<String> boards();

    CompletableFuture<List<LeaderboardEntry>> top(String board, int limit);

    long lastRefreshedAtMillis();
}