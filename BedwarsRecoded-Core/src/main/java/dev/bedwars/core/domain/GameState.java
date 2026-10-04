package dev.bedwars.core.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The match lifecycle state machine. Transitions are validated so an illegal
 * move (e.g. RUNNING &rarr; WAITING) fails loudly rather than corrupting state.
 */
public enum GameState {
    WAITING,
    COUNTDOWN,
    RUNNING,
    SUDDEN_DEATH,
    ENDED,
    ABORTED;

    private Set<GameState> successors() {
        return switch (this) {
            case WAITING -> EnumSet.of(COUNTDOWN, ABORTED);
            case COUNTDOWN -> EnumSet.of(RUNNING, WAITING, ABORTED);
            case RUNNING -> EnumSet.of(SUDDEN_DEATH, ENDED, ABORTED);
            case SUDDEN_DEATH -> EnumSet.of(ENDED, ABORTED);
            case ENDED, ABORTED -> EnumSet.noneOf(GameState.class);
        };
    }

    public boolean canTransitionTo(GameState next) {
        return successors().contains(next);
    }

    public boolean isTerminal() {
        return this == ENDED || this == ABORTED;
    }
}