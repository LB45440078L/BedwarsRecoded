package dev.bedwars.controller.config;

/**
 * Controller configuration, sourced from environment variables so the same image
 * runs in any cluster.
 */
public record ControllerConfig(
        String namespace,
        String gameServerSet,
        int httpPort,
        int minReplicas,
        int maxReplicas,
        int prewarmThreshold,
        long baseBackoffMillis,
        long maxBackoffMillis
) {

    public static ControllerConfig fromEnv() {
        return new ControllerConfig(
                env("BEDWARS_NAMESPACE", "bedwars"),
                env("BEDWARS_GAMESERVERSET", "bedwars-solo"),
                Integer.parseInt(env("BEDWARS_HTTP_PORT", "8080")),
                Integer.parseInt(env("BEDWARS_MIN_REPLICAS", "0")),
                Integer.parseInt(env("BEDWARS_MAX_REPLICAS", "50")),
                Integer.parseInt(env("BEDWARS_PREWARM_THRESHOLD", "2")),
                Long.parseLong(env("BEDWARS_BASE_BACKOFF_MS", "500")),
                Long.parseLong(env("BEDWARS_MAX_BACKOFF_MS", "10_000".replace("_", ""))));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}