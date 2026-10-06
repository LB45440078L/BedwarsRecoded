package dev.bedwars.core.reporting;

/**
 * Decides whether the plugin should switch the server's whitelist off at boot.
 *
 * <p>Why this exists: a Minecraft server can have its whitelist enabled by its own
 * default, and an <em>empty</em> whitelist then rejects everyone with
 * <em>"You are not whitelisted on this server!"</em>. That is fatal for a game server,
 * whose entire purpose is to accept the players the controller routes to it — the
 * controller's queue is the access control, not the whitelist. It stays configurable so
 * an operator who really wants a whitelist can keep one.
 */
public enum WhitelistEnforcement {

    /** Default: switch the whitelist off. A game server accepts what the controller routes. */
    OFF,
    /** Never touch it. */
    LEAVE;

    /** Parses a configured value; anything unknown (including the legacy {@code AUTO}) means {@link #OFF}. */
    public static WhitelistEnforcement parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LEAVE", "FALSE", "IGNORE", "NEVER", "KEEP" -> LEAVE;
            default -> OFF;
        };
    }

    /**
     * @param configured the {@code server.force-whitelist-off} value
     * @return true when the plugin should call {@code setWhitelist(false)}
     */
    public static boolean shouldDisable(WhitelistEnforcement configured) {
        return configured == OFF;
    }
}