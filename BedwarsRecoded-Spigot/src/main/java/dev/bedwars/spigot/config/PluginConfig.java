package dev.bedwars.spigot.config;

import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.core.config.DatabaseConfig;
import dev.bedwars.core.reporting.DeploymentMode;
import dev.bedwars.core.reporting.WhitelistEnforcement;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.regex.Pattern;

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
        int gamesPerServer,
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
        boolean jsonLogs,
        DeploymentMode mode,
        boolean persistenceEnabled,
        boolean templateEnabled,
        int disableReportingAfterFailures,
        int failureLogIntervalSeconds,
        WhitelistEnforcement whitelistEnforcement,
        DragonConfig dragon
) {

    /** Matches {@code ${NAME}} placeholders in configuration values. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

    public static PluginConfig from(FileConfiguration c, String defaultServerId) {
        TemplateSource source = switch (env("BEDWARS_TEMPLATE_SOURCE",
                c.getString("template.source", "LOCAL")).toUpperCase()) {
            case "S3" -> TemplateSource.S3;
            default -> TemplateSource.LOCAL;
        };
        DatabaseConfig db = new DatabaseConfig(
                env("BEDWARS_DB_HOST", c.getString("database.host", "127.0.0.1")),
                intEnv("BEDWARS_DB_PORT", c.getInt("database.port", 3306)),
                env("BEDWARS_DB_NAME", c.getString("database.name", "bedwars")),
                env("BEDWARS_DB_USER", c.getString("database.username", "bedwars")),
                env("BEDWARS_DB_PASSWORD", c.getString("database.password", "bedwars")),
                intEnv("BEDWARS_DB_POOL_SIZE", c.getInt("database.pool-size", 4)),
                10_000L,
                1_800_000L,
                "game-pod");

        return new PluginConfig(
                expand(env("BEDWARS_SERVER_ID", c.getString("server-id", defaultServerId))),
                env("BEDWARS_ARENA_GROUP", c.getString("arena.group", "solo")),
                intEnv("BEDWARS_TEAM_COUNT", c.getInt("arena.team-count", 2)),
                intEnv("BEDWARS_PLAYERS_PER_TEAM", c.getInt("arena.players-per-team", 2)),
                intEnv("BEDWARS_COUNTDOWN_SECONDS", c.getInt("arena.countdown-seconds", 15)),
                intEnv("BEDWARS_SUDDEN_DEATH_SECONDS", c.getInt("arena.sudden-death-after-seconds", 300)),
                c.getDouble("arena.void-y-threshold", 0.0),
                c.getDouble("arena.island-radius", 30.0),
                c.getDouble("arena.bed-protection-radius", 3.0),
                intEnv("BEDWARS_GAMES_PER_SERVER", c.getInt("arena.games-per-server", 1)),
                env("BEDWARS_TEMPLATE_NAME", c.getString("template.name", "Glacier")),
                env("BEDWARS_TEMPLATE_VERSION", c.getString("template.version", "1.0.0")),
                source,
                env("BEDWARS_TEMPLATE_LOCAL_PATH", c.getString("template.local-path", "templates")),
                env("BEDWARS_CONTROLLER_URL", c.getString("controller.base-url", "http://bedwars-controller:8080")),
                intEnv("BEDWARS_HEARTBEAT_SECONDS", c.getInt("controller.heartbeat-seconds", 15)),
                db,
                c.getInt("ranking.k-factor", 32),
                c.getInt("ranking.leaderboard-refresh-seconds", 60),
                c.getInt("ranking.leaderboard-page-size", 100),
                c.getBoolean("logging.json", true),
                DeploymentMode.parse(env("BEDWARS_DEPLOYMENT_MODE", c.getString("deployment.mode", "AUTO"))),
                c.getBoolean("persistence.enabled", true),
                Boolean.parseBoolean(env("BEDWARS_TEMPLATE_ENABLED",
                        String.valueOf(c.getBoolean("template.enabled", true)))),
                Math.max(1, c.getInt("deployment.disable-reporting-after-failures", 5)),
                Math.max(0, c.getInt("deployment.failure-log-interval-seconds", 300)),
                WhitelistEnforcement.parse(env("BEDWARS_WHITELIST",
                        c.getString("server.force-whitelist-off", "AUTO"))),
                dragon(c));
    }

    /**
     * Sudden-death dragons. See the Javadoc on {@link DragonConfig}: the values decide
     * whether a dragon spawns at all, how hard it knocks players back, and how much of
     * the map it tears up.
     */
    private static DragonConfig dragon(FileConfiguration c) {
        return new DragonConfig(
                Boolean.parseBoolean(env("BEDWARS_DRAGON_ENABLED",
                        String.valueOf(c.getBoolean("dragon.enabled", true)))),
                Math.max(0, intEnv("BEDWARS_DRAGON_BASE", c.getInt("dragon.base-per-match", 1))),
                Math.max(1.0, c.getDouble("dragon.health", 300.0)),
                Math.max(0.0, c.getDouble("dragon.spawn-height", 25.0)),
                Math.max(0.0, c.getDouble("dragon.knockback", 3.0)),
                Math.max(1.0, c.getDouble("dragon.knockback-radius", 10.0)),
                Math.max(1, intEnv("BEDWARS_DRAGON_KNOCKBACK_INTERVAL",
                        c.getInt("dragon.knockback-interval-ticks", 10))),
                Math.max(0, c.getInt("dragon.destroy-radius", 3)),
                Math.max(1, c.getInt("dragon.destroy-depth", 40)),
                Math.max(1, c.getInt("dragon.destroy-interval-ticks", 20)),
                Math.max(0.0, c.getDouble("dragon.damage", 0.0)));
    }

    /**
     * Dragon settings for a match's sudden-death phase.
     *
     * @param enabled               whether dragons spawn at all
     * @param basePerMatch          neutral dragons that attack every team
     * @param health                dragon health (so it is not killed instantly)
     * @param spawnHeight           blocks above the island/centre a dragon spawns
     * @param knockback             velocity magnitude applied to players near a dragon
     * @param knockbackRadius       how far a dragon reaches players
     * @param knockbackIntervalTicks ticks between knockback pulses
     * @param destroyRadius         block radius the dragon tears up as it flies
     * @param destroyDepth          how far below the dragon that damage reaches
     * @param destroyIntervalTicks  ticks between map-destruction pulses
     * @param damage                optional heart damage per knockback pulse (0 = none)
     */
    public record DragonConfig(
            boolean enabled,
            int basePerMatch,
            double health,
            double spawnHeight,
            double knockback,
            double knockbackRadius,
            int knockbackIntervalTicks,
            int destroyRadius,
            int destroyDepth,
            int destroyIntervalTicks,
            double damage
    ) {
        public static final DragonConfig DEFAULTS =
                new DragonConfig(true, 1, 300.0, 25.0, 3.0, 10.0, 10, 3, 40, 20, 0.0);
    }

    /** Environment variable wins over the YAML value when set and non-blank. */
    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Expands {@code ${NAME}} placeholders against the environment (then system
     * properties, then the local hostname for HOSTNAME/COMPUTERNAME).
     *
     * <p>Without this the shipped default {@code server-id: "pod-${HOSTNAME}"} stays
     * literal — pods reported the string {@code pod-${HOSTNAME}} to the controller
     * instead of their real identity.
     */
    static String expand(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        var matcher = PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = System.getenv(key);
            if (replacement == null) {
                replacement = System.getProperty(key);
            }
            if (replacement == null && ("HOSTNAME".equals(key) || "COMPUTERNAME".equals(key))) {
                replacement = localHostname();
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    replacement == null ? "" : replacement));
        }
        matcher.appendTail(out);
        String expanded = out.toString().trim();
        return expanded.isEmpty() ? "pod-local" : expanded;
    }

    private static String localHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static int intEnv(String key, int fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}