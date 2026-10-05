package dev.bedwars.controller.provision;

/** Which infrastructure backend is responsible for creating game servers. */
public enum ProvisionerKind {
    /** Kubernetes/OpenKruise {@code GameServerSet} (production default). */
    KUBERNETES,
    /** Plain Docker containers driven through the Docker CLI (single host / dev). */
    DOCKER,
    /** No infrastructure: scaling is disabled and no controller is authoritative. */
    NONE;

    /** Parses a configuration value, falling back to {@link #KUBERNETES}. */
    public static ProvisionerKind parse(String value) {
        if (value == null || value.isBlank()) {
            return KUBERNETES;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return KUBERNETES;
        }
    }
}
