package dev.bedwars.core.config;

/**
 * Database connection settings, loaded from YAML. One pool per component; the
 * game pod's pool is intentionally small (a match does little DB work), the
 * controller's is larger.
 */
public record DatabaseConfig(
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        long connectionTimeoutMs,
        long maxLifetimeMs,
        String poolName
) {
    public DatabaseConfig {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be >= 1");
        }
    }

    public String jdbcUrl() {
        // allowPublicKeyRetrieval is REQUIRED for MySQL 8's default caching_sha2_password
        // over a non-TLS link: without it Connector/J fails with
        // "Public Key Retrieval is not allowed" and the whole plugin runs stat-less.
        // sslMode=PREFERRED keeps TLS whenever the server offers it.
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=PREFERRED"
                + "&allowPublicKeyRetrieval=true"
                + "&characterEncoding=utf8"
                + "&serverTimezone=UTC"
                + "&rewriteBatchedStatements=true";
    }

    public static DatabaseConfig localDefaults(String poolName, int poolSize) {
        return new DatabaseConfig("127.0.0.1", 3306, "bedwars", "bedwars", "bedwars",
                poolSize, 10_000L, 1_800_000L, poolName);
    }
}