package dev.bedwars.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.bedwars.core.config.DatabaseConfig;
import org.slf4j.Logger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Thin HikariCP wrapper. No component ever holds a raw connection long-term;
 * everything goes through try-with-resources against {@link #connection()}.
 */
public final class Database implements AutoCloseable {

    private final HikariDataSource dataSource;
    private final Logger log;

    public Database(DatabaseConfig config, Logger log) {
        this.log = log;
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(1);
        hikari.setPoolName("bedwars-" + config.poolName());
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setMaxLifetime(config.maxLifetimeMs());
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        this.dataSource = new HikariDataSource(hikari);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean isHealthy() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (SQLException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}