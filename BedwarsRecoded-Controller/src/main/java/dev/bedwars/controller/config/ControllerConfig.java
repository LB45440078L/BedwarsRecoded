package dev.bedwars.controller.config;

import dev.bedwars.controller.provision.ProvisionerKind;

/**
 * Controller configuration, sourced from environment variables so the same image
 * runs in any cluster or on a single host.
 *
 * <p>Grouped into {@link Provisioning} and {@link Security} so the growing config
 * stays readable instead of becoming one flat record with twenty positional fields.
 */
public record ControllerConfig(
        String namespace,
        String gameServerSet,
        int httpPort,
        int prewarmThreshold,
        long baseBackoffMillis,
        long maxBackoffMillis,
        Provisioning provisioning,
        Security security) {

    /**
     * How game servers are created and reclaimed.
     *
     * @param gamesPerServer how many concurrent games one dedicated server hosts
     *                       (capacity = servers x gamesPerServer)
     * @param idleMinutes    how long a fleet must be idle before an idle server is reclaimed
     */
    public record Provisioning(
            ProvisionerKind kind,
            int minServers,
            int maxServers,
            int gamesPerServer,
            boolean scaleDownEnabled,
            int idleMinutes,
            String serverPrefix,
            String dockerImage,
            String containerMemory,
            String arenaGroup,
            String controllerAdvertiseUrl) {

        public long idleMillis() {
            return idleMinutes * 60_000L;
        }
    }

    /** Shared-secret protection for the mutating HTTP endpoints. */
    public record Security(String apiToken) {
        public boolean enabled() {
            return apiToken != null && !apiToken.isBlank();
        }
    }

    /** The base URL a freshly provisioned game server should use to reach the controller. */
    public String controllerUrlForGameServers() {
        return provisioning.controllerAdvertiseUrl();
    }

    public static ControllerConfig fromEnv() {
        Provisioning provisioning = new Provisioning(
                ProvisionerKind.parse(env("BEDWARS_PROVISIONER", "KUBERNETES")),
                intEnv("BEDWARS_MIN_SERVERS", intEnv("BEDWARS_MIN_REPLICAS", 2)),
                intEnv("BEDWARS_MAX_SERVERS", intEnv("BEDWARS_MAX_REPLICAS", 20)),
                intEnv("BEDWARS_GAMES_PER_SERVER", 25),
                boolEnv("BEDWARS_SCALE_DOWN_ENABLED", true),
                intEnv("BEDWARS_IDLE_MINUTES", 10),
                env("BEDWARS_SERVER_PREFIX", "bedwars-game"),
                env("BEDWARS_DOCKER_IMAGE", "bedwars-recoded-game:latest"),
                env("BEDWARS_CONTAINER_MEMORY", "1536m"),
                env("BEDWARS_ARENA_GROUP", "solo"),
                env("BEDWARS_CONTROLLER_ADVERTISE_URL", "http://host.docker.internal:8080"));

        Security security = new Security(env("BEDWARS_API_TOKEN", ""));

        return new ControllerConfig(
                env("BEDWARS_NAMESPACE", "bedwars"),
                env("BEDWARS_GAMESERVERSET", "bedwars-solo"),
                intEnv("BEDWARS_HTTP_PORT", 8080),
                intEnv("BEDWARS_PREWARM_THRESHOLD", 2),
                longEnv("BEDWARS_BASE_BACKOFF_MS", 500L),
                longEnv("BEDWARS_MAX_BACKOFF_MS", 10_000L),
                provisioning,
                security);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intEnv(String key, int fallback) {
        try {
            return Integer.parseInt(env(key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long longEnv(String key, long fallback) {
        try {
            return Long.parseLong(env(key, Long.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolEnv(String key, boolean fallback) {
        return Boolean.parseBoolean(env(key, Boolean.toString(fallback)));
    }
}
