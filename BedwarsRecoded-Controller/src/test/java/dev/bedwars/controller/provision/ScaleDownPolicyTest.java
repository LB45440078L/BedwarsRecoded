package dev.bedwars.controller.provision;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The idle scale-down rule, tested directly. This is the guard that prevents the
 * controller from ever reclaiming a server that is needed or active.
 */
class ScaleDownPolicyTest {

    private static final long MINUTE = 60_000L;

    @Test
    void doesNotScaleDownWhilePlayersWait() {
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 1, 10 * MINUTE);
        assertThat(policy.evaluate(5, 3, 1, 60 * MINUTE).scaleDown()).isFalse();
    }

    @Test
    void doesNotScaleDownWhenNoIdleServerToReclaim() {
        // readyServers == 0 means every server is either running a game or starting;
        // reconnecting one would kill an active match.
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 1, 10 * MINUTE);
        assertThat(policy.evaluate(5, 0, 0, 60 * MINUTE).scaleDown()).isFalse();
    }

    @Test
    void doesNotScaleDownAtMinimumCapacity() {
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 2, 10 * MINUTE);
        assertThat(policy.evaluate(2, 1, 0, 60 * MINUTE).scaleDown()).isFalse();
    }

    @Test
    void doesNotScaleDownBeforeTheIdleWindow() {
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 1, 10 * MINUTE);
        assertThat(policy.evaluate(4, 2, 0, 9 * MINUTE).scaleDown()).isFalse();
    }

    @Test
    void scalesDownWhenIdleAboveMinimum() {
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 1, 10 * MINUTE);
        ScaleDownPolicy.Decision decision = policy.evaluate(4, 2, 0, 11 * MINUTE);
        assertThat(decision.scaleDown()).isTrue();
        assertThat(decision.reason()).contains("idle");
    }

    @Test
    void disabledPolicyNeverScalesDown() {
        ScaleDownPolicy policy = new ScaleDownPolicy(false, 1, 0);
        assertThat(policy.evaluate(9, 9, 0, Long.MAX_VALUE / 2).scaleDown()).isFalse();
    }

    @Test
    void nextTargetNeverGoesBelowMinimum() {
        ScaleDownPolicy policy = new ScaleDownPolicy(true, 2, MINUTE);
        assertThat(policy.nextTarget(5)).isEqualTo(4);
        assertThat(policy.nextTarget(2)).isEqualTo(2);
        assertThat(policy.nextTarget(1)).isEqualTo(2);
    }
}
