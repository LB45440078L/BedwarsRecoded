package dev.bedwars.core.persistence;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persists per-player quick-buy layouts so they survive across pods and proxies
 * (constraint: no durable player state inside a pod). Writes replace the whole
 * layout in a transaction, keyed by UUID.
 */
public final class QuickBuyRepository implements AutoCloseable {

    private final Database database;
    private final Logger log;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public QuickBuyRepository(Database database, Logger log) {
        this.database = database;
        this.log = log;
    }

    public CompletableFuture<List<String>> load(UUID player) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT item_id FROM quick_buy WHERE uuid = ? ORDER BY slot ASC";
            List<String> items = new ArrayList<>();
            try (Connection c = database.connection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(rs.getString("item_id"));
                    }
                }
            } catch (SQLException e) {
                log.error("Failed to load quick buy for {}", player, e);
            }
            return List.copyOf(items);
        }, executor);
    }

    public CompletableFuture<Void> save(UUID player, List<String> items) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = database.connection()) {
                boolean autoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM quick_buy WHERE uuid = ?")) {
                        del.setString(1, player.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO quick_buy (uuid, slot, item_id) VALUES (?, ?, ?)")) {
                        int slot = 0;
                        for (String item : items) {
                            ins.setString(1, player.toString());
                            ins.setInt(2, slot++);
                            ins.setString(3, item);
                            ins.addBatch();
                        }
                        ins.executeBatch();
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                log.error("Failed to save quick buy for {}", player, e);
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    @Override
    public void close() {
        executor.close();
    }
}