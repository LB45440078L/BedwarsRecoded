package dev.bedwars.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JDBC URL must work against a stock MySQL 8, whose default authentication
 * plugin is {@code caching_sha2_password}. Without {@code allowPublicKeyRetrieval}
 * the driver refuses to connect over a non-TLS link and the plugin silently runs
 * without stats — which is exactly what happened on the real test server.
 */
class DatabaseConfigTest {

    private static DatabaseConfig config() {
        return new DatabaseConfig("db.internal", 3306, "bedwars", "user", "secret", 4, 10_000L, 60_000L, "game-pod");
    }

    @Test
    void urlAllowsPublicKeyRetrievalForMysql8Auth() {
        assertThat(config().jdbcUrl()).contains("allowPublicKeyRetrieval=true");
    }

    @Test
    void urlPrefersTlsAndSetsUtf8AndUtc() {
        String url = config().jdbcUrl();

        assertThat(url).startsWith("jdbc:mysql://db.internal:3306/bedwars?");
        assertThat(url).contains("sslMode=PREFERRED");
        assertThat(url).contains("characterEncoding=utf8");
        assertThat(url).contains("serverTimezone=UTC");
        // useSSL is deprecated in Connector/J 8 and must not be the mechanism used.
        assertThat(url).doesNotContain("useSSL=");
    }

    @Test
    void urlBatchesRewritesForAdditiveWrites() {
        assertThat(config().jdbcUrl()).contains("rewriteBatchedStatements=true");
    }

    @Test
    void poolSizeMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new DatabaseConfig("h", 3306, "d", "u", "p", 0, 1L, 2L, "n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}