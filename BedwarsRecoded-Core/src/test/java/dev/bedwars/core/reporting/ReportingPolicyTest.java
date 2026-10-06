package dev.bedwars.core.reporting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the console-noise defect: a controller that is down must not
 * produce a log line per heartbeat forever.
 */
class ReportingPolicyTest {

    @Test
    void aFreshPolicySends() {
        ReportingPolicy policy = new ReportingPolicy();

        assertThat(policy.shouldSend()).isTrue();
        assertThat(policy.autoDisabled()).isFalse();
        assertThat(policy.describe()).isEqualTo("enabled");
    }

    @Test
    void sendsUntilItIsAutoDisabled() {
        ReportingPolicy policy = new ReportingPolicy(3, 1_000L);

        assertThat(policy.shouldSend()).isTrue();
        policy.recordFailure(0L);
        policy.recordFailure(0L);
        assertThat(policy.shouldSend()).isTrue();

        policy.recordFailure(0L); // third consecutive failure hits the threshold
        assertThat(policy.shouldSend()).isFalse();
        assertThat(policy.autoDisabled()).isTrue();
        assertThat(policy.describe()).isEqualTo("disabled after 3 consecutive failures");
    }

    @Test
    void failureLoggingIsThrottledToTheInterval() {
        ReportingPolicy policy = new ReportingPolicy(10, 5_000L);

        assertThat(policy.shouldLogFailure(1_000L)).isTrue();
        policy.recordFailure(1_000L);
        // Within the window: no repeat log.
        assertThat(policy.shouldLogFailure(2_000L)).isFalse();
        assertThat(policy.shouldLogFailure(5_999L)).isFalse();
        // Past the window: log again.
        assertThat(policy.shouldLogFailure(6_000L)).isTrue();
    }

    @Test
    void oneSuccessResetsTheFailureCountAndTheLogWindow() {
        ReportingPolicy policy = new ReportingPolicy(3, 5_000L);
        policy.recordFailure(1_000L);
        policy.recordFailure(1_000L);

        policy.recordSuccess();

        assertThat(policy.consecutiveFailures()).isZero();
        assertThat(policy.autoDisabled()).isFalse();
        assertThat(policy.shouldSend()).isTrue();
        assertThat(policy.shouldLogFailure(1_001L)).isTrue();
    }

    @Test
    void aLaterFailureDoesNotReReportTheAutoDisable() {
        ReportingPolicy policy = new ReportingPolicy(1, 1_000L);

        assertThat(policy.recordFailure(0L)).as("first disabling failure").isTrue();
        assertThat(policy.recordFailure(0L)).as("already disabled").isFalse();
    }

    @Test
    void defaultsAreSaneForAGameServerHeartbeat() {
        ReportingPolicy policy = new ReportingPolicy();

        for (int i = 0; i < ReportingPolicy.DEFAULT_DISABLE_AFTER_FAILURES; i++) {
            policy.recordFailure(0L);
        }
        assertThat(policy.autoDisabled()).isTrue();
    }
}