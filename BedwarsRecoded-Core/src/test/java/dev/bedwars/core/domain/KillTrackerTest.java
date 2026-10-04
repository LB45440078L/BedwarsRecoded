package dev.bedwars.core.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KillTrackerTest {

    private final UUID victim = UUID.randomUUID();
    private final UUID attacker = UUID.randomUUID();

    @Test
    void creditsKillerWithinWindow() {
        KillTracker tracker = new KillTracker(8_000L);
        tracker.recordDamage(victim, attacker, 1_000L);
        assertThat(tracker.killerOf(victim, 5_000L)).contains(attacker);
    }

    @Test
    void ignoresKillerOutsideWindow() {
        KillTracker tracker = new KillTracker(8_000L);
        tracker.recordDamage(victim, attacker, 1_000L);
        assertThat(tracker.killerOf(victim, 20_000L)).isEmpty();
    }

    @Test
    void selfDamageIsIgnored() {
        KillTracker tracker = new KillTracker(8_000L);
        tracker.recordDamage(victim, victim, 1_000L);
        assertThat(tracker.killerOf(victim, 1_500L)).isEmpty();
    }

    @Test
    void streaksIncrementAndResetOnDeath() {
        KillTracker tracker = new KillTracker(8_000L);
        tracker.onKill(attacker);
        tracker.onKill(attacker);
        assertThat(tracker.streakOf(attacker)).isEqualTo(2);
        tracker.onDeath(attacker);
        assertThat(tracker.streakOf(attacker)).isZero();
    }
}