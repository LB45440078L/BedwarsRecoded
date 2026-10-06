package dev.bedwars.controller.provision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One dispatch retry must not start a fleet: these are the rules that keep pre-warming to
 * one server per boot window.
 */
class PrewarmGuardTest {

    private static final long GRACE = 30_000L;

    @Test
    void prewarmsWhenNothingIsBootingAndTheGraceWindowHasPassed() {
        assertThat(PrewarmGuard.shouldPrewarm(0, 0, GRACE, GRACE)).isTrue();
        assertThat(PrewarmGuard.shouldPrewarm(2, 2, 5 * GRACE, GRACE)).isTrue();
    }

    @Test
    void refusesWhileAServerIsStillBooting() {
        // The provisioner started a second server but only one has registered.
        assertThat(PrewarmGuard.shouldPrewarm(1, 0, 10 * GRACE, GRACE)).isFalse();
        assertThat(PrewarmGuard.shouldPrewarm(13, 1, 10 * GRACE, GRACE)).isFalse();
    }

    @Test
    void refusesAgainInsideTheGraceWindowEvenWhenCountsMatch() {
        assertThat(PrewarmGuard.shouldPrewarm(1, 1, 0L, GRACE)).isFalse();
        assertThat(PrewarmGuard.shouldPrewarm(1, 1, GRACE - 1, GRACE)).isFalse();
    }

    @Test
    void permitsAgainOnceTheBootingServerRegisters() {
        // Counts match again and the window has passed: demand may be pre-warmed.
        assertThat(PrewarmGuard.shouldPrewarm(1, 1, GRACE, GRACE)).isTrue();
    }
}
