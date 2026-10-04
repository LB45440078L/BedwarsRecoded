package dev.bedwars.core.persistence;

import dev.bedwars.api.dto.PlayerStatDelta;
import dev.bedwars.api.dto.PlayerStats;
import dev.bedwars.api.service.StatsService;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDBC implementation of {@link StatsService}. Every call runs on a virtual
 * thread so the server tick thread is never blocked (constraint: async all I/O).
 * Writes are additive so concurrent pods cannot clobber each other's counters.
 */
public final class StatsRepository implements StatsService, AutoCloseable {

    private final Database database;
    private final Logger log;
    private final int baselineElo;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public StatsRepository(Database database, Logger log, int baselineElo) {
        this.database = database;
        this.log = log;
        this.baselineElo = baselineElo;
    }

    @Override
    public CompletableFuture<PlayerStats> load(UUID uuid, String username) {
        return CompletableFuture.supplyAsync(() -> loadSync(uuid, username), executor);
    }

    private PlayerStats loadSync(UUID uuid, String username) {
        String select = """
                SELECT username, kills, deaths, final_kills, final_deaths, wins, losses,
                       beds_broken, beds_lost, games_played, experience, elo
                FROM player_stats WHERE uuid = ?
                """;
        try (Connection c = database.connection()) {
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return mapRow(uuid, rs);
                    }
                }
            }
            ensureRow(c, uuid, username);
            return PlayerStats.fresh(uuid, username, baselineElo);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load stats for " + uuid, e);
        }
    }

    private void ensureRow(Connection c, UUID uuid, String username) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT IGNORE INTO player_stats (uuid, username, elo) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, username);
            ps.setInt(3, baselineElo);
            ps.executeUpdate();
        }
    }

    private static PlayerStats mapRow(UUID uuid, ResultSet rs) throws SQLException {
        return new PlayerStats(
                uuid,
                rs.getString("username"),
                rs.getInt("kills"),
                rs.getInt("deaths"),
                rs.getInt("final_kills"),
                rs.getInt("final_deaths"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getInt("beds_broken"),
                rs.getInt("beds_lost"),
                rs.getInt("games_played"),
                rs.getLong("experience"),
                rs.getInt("elo"));
    }

    @Override
    public CompletableFuture<Void> apply(PlayerStatDelta delta) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = database.connection()) {
                ensureRow(c, delta.uuid(), delta.uuid().toString());
                String update = """
                        UPDATE player_stats SET
                            kills = kills + ?, final_kills = final_kills + ?,
                            deaths = deaths + ?, final_deaths = final_deaths + ?,
                            beds_broken = beds_broken + ?, beds_lost = beds_lost + ?,
                            wins = wins + ?, losses = losses + ?,
                            games_played = games_played + 1, experience = experience + ?
                        WHERE uuid = ?
                        """;
                try (PreparedStatement ps = c.prepareStatement(update)) {
                    ps.setInt(1, delta.kills());
                    ps.setInt(2, delta.finalKills());
                    ps.setInt(3, delta.deaths());
                    ps.setInt(4, delta.finalDeaths());
                    ps.setInt(5, delta.bedsBroken());
                    ps.setInt(6, delta.bedsLost());
                    ps.setInt(7, delta.winner() ? 1 : 0);
                    ps.setInt(8, delta.winner() ? 0 : 1);
                    ps.setLong(9, delta.experienceGained());
                    ps.setString(10, delta.uuid().toString());
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                log.error("Failed to apply stat delta for {}", delta.uuid(), e);
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> updateElo(UUID uuid, int newElo) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = database.connection();
                 PreparedStatement ps = c.prepareStatement("UPDATE player_stats SET elo = ? WHERE uuid = ?")) {
                ps.setInt(1, newElo);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                log.error("Failed to update ELO for {}", uuid, e);
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.close();
    }
}