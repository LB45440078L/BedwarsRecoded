package dev.bedwars.core.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The ordered migration catalogue. To evolve the schema: append a new
 * {@link Migration} with the next version number — never modify an existing one.
 */
public final class Migrations {

    private Migrations() {
    }

    public static List<Migration> all() {
        List<Migration> migrations = new ArrayList<>(List.of(
                Migration.of(1, "player_stats table",
                        """
                        CREATE TABLE IF NOT EXISTS player_stats (
                            uuid         CHAR(36)     NOT NULL PRIMARY KEY,
                            username     VARCHAR(16)  NOT NULL,
                            kills        INT          NOT NULL DEFAULT 0,
                            deaths       INT          NOT NULL DEFAULT 0,
                            final_kills  INT          NOT NULL DEFAULT 0,
                            final_deaths INT          NOT NULL DEFAULT 0,
                            wins         INT          NOT NULL DEFAULT 0,
                            losses       INT          NOT NULL DEFAULT 0,
                            beds_broken  INT          NOT NULL DEFAULT 0,
                            beds_lost    INT          NOT NULL DEFAULT 0,
                            games_played INT          NOT NULL DEFAULT 0,
                            experience   BIGINT       NOT NULL DEFAULT 0,
                            elo          INT          NOT NULL DEFAULT 1000,
                            server_id    VARCHAR(64)  NULL,
                            updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                      ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                        """),
                Migration.of(2, "index player_stats.elo for leaderboards",
                        "CREATE INDEX idx_player_stats_elo ON player_stats (elo)"),
                Migration.of(3, "index player_stats.wins for leaderboards",
                        "CREATE INDEX idx_player_stats_wins ON player_stats (wins)")
        ));
        migrations.sort(Comparator.comparingInt(Migration::version));
        return migrations;
    }

    public static int latestVersion() {
        return all().stream().mapToInt(Migration::version).max().orElse(0);
    }
}