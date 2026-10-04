package dev.bedwars.api.dto;

/** High-level phase of a single match, independent of pod lifecycle. */
public enum GamePhase {
    /** Accepting players, no countdown running. */
    WAITING,
    /** Countdown ticking down before the match begins. */
    COUNTDOWN,
    /** Normal play. */
    RUNNING,
    /** Sudden death: beds auto-destroyed, all generators maxed. */
    SUDDEN_DEATH,
    /** Match finished, results being flushed. */
    ENDED
}