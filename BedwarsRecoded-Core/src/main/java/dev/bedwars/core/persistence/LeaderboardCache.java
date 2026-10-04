package dev.bedwars.core.persistence;

import dev.bedwars.api.service.LeaderboardEntry;
import dev.bedwars.api.service.LeaderboardService;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cached leaderboards. Queries run on a schedule (never per request); reads are
 * served from memory. Column names are a fixed allow-list to avoid injection.
 */
public final class LeaderboardCache implements LeaderboardService, AutoCloseable {

    private static final Map<String, String> BOARD_COLUMNS = Map.of(
            "wins", "wins",
            "elo", "elo",
            "kills", "kills",
            "beds_broken", "beds_broken");

    private final Database database;
    private final Logger log;
    private final int pageSize;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, List<LeaderboardEntry>> cache = new ConcurrentHashMap<>();
    private final AtomicLong lastRefreshed = new AtomicLong(0L);

    public LeaderboardCache(Database database, Logger log, int pageSize) {
        this.database = database;
        this.log = log;
        this.pageSize = pageSize;
    }

    @Override
    public List<String> boards() {
        return List.copyOf(BOARD_COLUMNS.keySet());
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> top(String board, int limit) {
        List<LeaderboardEntry> rows = cache.getOrDefault(board, List.of());
        return CompletableFuture.completedFuture(rows.stream().limit(limit).toList());
    }

    @Override
    public long lastRefreshedAtMillis() {
        return lastRefreshed.get();
    }

    /** Refreshes every board. Intended to be called on a fixed schedule. */
    public CompletableFuture<Void> refresh() {
        return CompletableFuture.runAsync(() -> {
            for (String board : BOARD_COLUMNS.keySet()) {
                try {
                    cache.put(board, query(board));
                } catch (SQLException e) {
                    log.error("Leaderboard refresh failed for board {}", board, e);
                }
            }
            lastRefreshed.set(System.currentTimeMillis());
        }, executor);
    }

    private List<LeaderboardEntry> query(String board) throws SQLException {
        String column = BOARD_COLUMNS.get(board);
        String sql = "SELECT uuid, username, " + column + " AS value FROM player_stats ORDER BY " + column + " DESC LIMIT ?";
        List<LeaderboardEntry> entries = new ArrayList<>();
        try (Connection c = database.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, pageSize);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    entries.add(new LeaderboardEntry(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("username"),
                            rs.getLong("value"),
                            rank++));
                }
            }
        }
        return List.copyOf(entries);
    }

    @Override
    public void close() {
        executor.close();
    }
}