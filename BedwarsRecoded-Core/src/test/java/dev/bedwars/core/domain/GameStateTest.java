package dev.bedwars.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameStateTest {

    @Test
    void legalTransitionsArePermitted() {
        assertThat(GameState.WAITING.canTransitionTo(GameState.COUNTDOWN)).isTrue();
        assertThat(GameState.COUNTDOWN.canTransitionTo(GameState.RUNNING)).isTrue();
        assertThat(GameState.COUNTDOWN.canTransitionTo(GameState.WAITING)).isTrue();
        assertThat(GameState.RUNNING.canTransitionTo(GameState.SUDDEN_DEATH)).isTrue();
        assertThat(GameState.RUNNING.canTransitionTo(GameState.ENDED)).isTrue();
        assertThat(GameState.SUDDEN_DEATH.canTransitionTo(GameState.ENDED)).isTrue();
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThat(GameState.WAITING.canTransitionTo(GameState.RUNNING)).isFalse();
        assertThat(GameState.ENDED.canTransitionTo(GameState.RUNNING)).isFalse();
        assertThat(GameState.ABORTED.canTransitionTo(GameState.WAITING)).isFalse();
        assertThat(GameState.RUNNING.canTransitionTo(GameState.WAITING)).isFalse();
    }

    @Test
    void terminalStatesHaveNoSuccessors() {
        assertThat(GameState.ENDED.isTerminal()).isTrue();
        assertThat(GameState.ABORTED.isTerminal()).isTrue();
        assertThat(GameState.RUNNING.isTerminal()).isFalse();
    }
}