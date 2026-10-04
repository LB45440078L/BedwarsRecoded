package dev.bedwars.spigot.config;

import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.core.config.DatabaseConfig;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Typed view over {@code config.yml}. Keeps the plugin's bootstrap free of
 * scattered {@code getString} calls.
 */
public record PluginConfig(
        String serverId,
        String arenaGroup,
        int teamCount,
        int playersPerTeam,
        int countdownSeconds,
        int suddenDeathAfterSeconds,
        double voidY,
        double islandRadius,
        double bedProtectionRadius,
        String templateName,
        String templateVersion,
        TemplateSource templateSource,
        String localTemplatePath,
        String controllerBaseUrl,
        int heartbeatSeconds,
        DatabaseConfig database,
        int kFactor,
        int leaderboardRefreshSeconds,
        int leaderboardPageSize,
        boolean jsonLogs
) {

    public static PluginConfig from(FileConfiguration c, String defaultServerId) {
        TemplateSource source = switch (c.getString("template.source", "LOCAL").toUpperCase()) {
            case "S3" -> TemplateSource.S3;
            default -> TemplateSource.LOCAL;
        };
        DatabaseConfig db = new DatabaseConfig(
                c.getString("database.host", "127.0.0.1"),
                c.getInt("database.port", 3306),
                c.getString("database.name", "bedwars"),
                c.getString("database.username", "bedwars"),
                c.getString("database.password", "bedwars"),
                c.getInt("database.pool-size", 4),
                10_000L,
                1_800_000L,
                "game-pod");

        return new PluginConfig(
                c.getString("server-id", defaultServerId),
                c.getString("arena.group", "solo"),
                c.getInt("arena.team-count", 2),
                c.getInt("arena.players-per-team", 2),
                c.getInt("arena.countdown-seconds", 15),
                c.getInt("arena.sudden-death-after-seconds", 300),
                c.getDouble("arena.void-y-threshold", 0.0),
                c.getDouble("arena.island-radius", 30.0),
                c.getDouble("arena.bed-protection-radius", 3.0),
                c.getString("template.name", "Glacier"),
                c.getString("template.version", "1.0.0"),
                source,
                c.getString("template.local-path", "templates/Glacier"),
                c.getString("controller.base-url", "http://bedwars-controller:8080"),
                c.getInt("controller.heartbeat-seconds", 15),
                db,
                c.getInt("ranking.k-factor", 32),
                c.getInt("ranking.leaderboard-refresh-seconds", 60),
                c.getInt("ranking.leaderboard-page-size", 100),
                c.getBoolean("logging.json", true));
    }
}