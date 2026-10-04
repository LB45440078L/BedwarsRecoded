package dev.bedwars.core.persistence;

import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Applies forward migrations idempotently. The {@code schema_version} table is
 * created on first run; each migration runs inside a transaction and is recorded
 * only if every statement succeeded. Re-running is a no-op.
 */
public final class SchemaMigrator {

    private static final String CREATE_VERSION_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version     INT          NOT NULL PRIMARY KEY,
                description VARCHAR(255) NOT NULL,
                applied_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """;

    private final Database database;
    private final Logger log;

    public SchemaMigrator(Database database, Logger log) {
        this.database = database;
        this.log = log;
    }

    public void migrate(List<Migration> migrations) {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute(CREATE_VERSION_TABLE);
            }
            int current = currentVersion(connection);
            log.info("Schema at version {}, latest available {}", current, Migrations.latestVersion());

            for (Migration migration : migrations) {
                if (migration.version() <= current) {
                    continue;
                }
                apply(connection, migration);
                current = migration.version();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema migration failed", e);
        }
    }

    private void apply(Connection connection, Migration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (String sql : migration.statements()) {
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO schema_version (version, description) VALUES (?, ?)")) {
                ps.setInt(1, migration.version());
                ps.setString(2, migration.description());
                ps.executeUpdate();
            }
            connection.commit();
            log.info("Applied migration v{} ({})", migration.version(), migration.description());
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public int currentVersion() {
        try (Connection c = database.connection()) {
            return currentVersion(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read schema version", e);
        }
    }
}