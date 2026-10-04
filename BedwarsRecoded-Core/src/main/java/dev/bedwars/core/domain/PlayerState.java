package dev.bedwars.core.domain;

/** Per-player lifecycle inside a match. */
public enum PlayerState {
    /** In the match, alive. */
    ALIVE,
    /** Killed while their team's bed still stands; will respawn. */
    RESPAWNING,
    /** Killed with no bed: out of the match. */
    ELIMINATED,
    /** Joined as a spectator after being eliminated. */
    SPECTATOR,
    /** Dropped connection; eligible for reconnect within the grace window. */
    DISCONNECTED
}