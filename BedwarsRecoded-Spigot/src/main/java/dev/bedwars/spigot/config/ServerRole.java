package dev.bedwars.spigot.config;

/**
 * What kind of server this process is.
 *
 * <p>The network has two roles, and one image serves both:
 *
 * <ul>
 *   <li>{@link #GAME} — a dedicated match host. It reports its free match slots to the
 *       controller so players can be routed to it, and it hosts one or more BedWars
 *       matches (the ephemeral, disposable pods).</li>
 *   <li>{@link #LOBBY} — the persistent hub every player lands on after connecting to
 *       the proxy. It hosts no matches and must <em>never</em> report capacity, or the
 *       controller would hand it players as if it were a game server. Its whole job is
 *       to show signs/NPCs and ask the proxy to send a player into a game.</li>
 * </ul>
 *
 * <p>Set with {@code server.role} in {@code config.yml}, or {@code BEDWARS_ROLE} in the
 * environment (the environment wins, so one image serves both by deployment).
 */
public enum ServerRole {

    GAME,
    LOBBY;

    /** Unknown or blank values fall back to {@link #GAME}, the historical behaviour. */
    public static ServerRole parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return GAME;
        }
        return switch (raw.trim().toUpperCase()) {
            case "LOBBY", "HUB", "PROXY_LOBBY" -> LOBBY;
            default -> GAME;
        };
    }

    public boolean isLobby() {
        return this == LOBBY;
    }
}
