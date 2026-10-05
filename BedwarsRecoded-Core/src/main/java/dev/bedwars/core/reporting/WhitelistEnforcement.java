package dev.bedwars.core.reporting;

/**
 * Decides whether the plugin should switch the server's whitelist off at boot.
 *
 * <p>Why this exists: a Minecraft server can have its whitelist enabled by its own
 * default, and an *empty* whitelist then rejects everyone with
 * <em>"You are not whitelisted on this server!"</em>. That is fatal for a game pod,
 * whose entire purpose is to accept the players the controller routes to it — the
 * queue is the access control, not the whitelist. It is equally wrong to silently
 * change an operator's whitelist on a normal server, so this is mode-aware and
 * configurable rather than unconditional.
 */
public enum WhitelistEnforcement {

    /**
     * Default: switch the whitelist off on a game pod (where the controller decides
     * who plays), and leave a standalone server's whitelist exactly as the operator
     * set it.
     */
    AUTO,
    /** Always switch it off, whatever the mode. */
    OFF,
    /** Never touch it. */
    LEAVE;

    /** Parses a configured value; anything unknown means {@link #AUTO}. */
    public static WhitelistEnforcement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "OFF", "TRUE", "DISABLE", "DISABLED", "ALWAYS" -> OFF;
            case "LEAVE", "FALSE", "IGNORE", "NEVER", "KEEP" -> LEAVE;
            default -> AUTO;
        };
    }

    /**
     * @param configured the {@code server.force-whitelist-off} value
     * @param mode       the resolved deployment mode
     * @return true when the plugin should call {@code setWhitelist(false)}
     */
    public static boolean shouldDisable(WhitelistEnforcement configured, DeploymentMode mode) {
        return switch (configured) {
            case OFF -> true;
            case LEAVE -> false;
            case AUTO -> mode == DeploymentMode.POD;
        };
    }
}