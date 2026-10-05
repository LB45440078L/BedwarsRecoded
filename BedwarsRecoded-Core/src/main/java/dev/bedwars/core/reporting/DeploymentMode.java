package dev.bedwars.core.reporting;

/**
 * How this server runs the plugin. This is the single switch that decides whether
 * the plugin behaves as a Kubernetes game pod or as an ordinary, long-lived server.
 *
 * <ul>
 *   <li>{@link #POD} — an ephemeral per-match pod. It reports its state to the
 *       controller (ready / heartbeat / ended), pulls its world from the template
 *       store, and writes stats to the shared database.</li>
 *   <li>{@link #STANDALONE} — a normal server (a dev box, a single-host setup, or a
 *       plain "basic mode" JVM). No controller exists, so the plugin makes
 *       <b>no</b> controller requests at all and never spawns a pod lifecycle.</li>
 *   <li>{@link #AUTO} — decide at boot by probing the controller's {@code /healthz}.
 *       This is the default, so a plugin dropped into a plain server stops trying to
 *       reach a controller that is not there.</li>
 * </ul>
 */
public enum DeploymentMode {

    POD,
    STANDALONE,
    AUTO;

    /** Parses a configured value, defaulting to {@link #AUTO} for anything unknown. */
    public static DeploymentMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "POD", "KUBERNETES", "K8S" -> POD;
            case "STANDALONE", "STANDALONE_SERVER", "LOCAL", "BASIC" -> STANDALONE;
            default -> AUTO;
        };
    }

    /**
     * Resolves the configured mode against a live reachability probe.
     *
     * @param configured          the {@code deployment.mode} value
     * @param controllerReachable whether the controller answered at boot
     * @return {@link #POD} or {@link #STANDALONE} — never {@link #AUTO}
     */
    public static DeploymentMode resolve(DeploymentMode configured, boolean controllerReachable) {
        return switch (configured) {
            case POD -> POD;
            case STANDALONE -> STANDALONE;
            case AUTO -> controllerReachable ? POD : STANDALONE;
        };
    }

    /** True when this mode talks to a controller and participates in pod lifecycle. */
    public boolean reportsToController() {
        return this == POD;
    }
}