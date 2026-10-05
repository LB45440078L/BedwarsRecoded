package dev.bedwars.core.reporting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A standalone server must make no controller requests at all. */
class DeploymentModeTest {

    @Test
    void parsesConfiguredValuesCaseInsensitively() {
        assertThat(DeploymentMode.parse("pod")).isEqualTo(DeploymentMode.POD);
        assertThat(DeploymentMode.parse(" K8S ")).isEqualTo(DeploymentMode.POD);
        assertThat(DeploymentMode.parse("standalone")).isEqualTo(DeploymentMode.STANDALONE);
        assertThat(DeploymentMode.parse("LOCAL")).isEqualTo(DeploymentMode.STANDALONE);
        assertThat(DeploymentMode.parse("basic")).isEqualTo(DeploymentMode.STANDALONE);
        assertThat(DeploymentMode.parse("auto")).isEqualTo(DeploymentMode.AUTO);
    }

    @Test
    void blankOrUnknownValuesDefaultToAuto() {
        assertThat(DeploymentMode.parse(null)).isEqualTo(DeploymentMode.AUTO);
        assertThat(DeploymentMode.parse("")).isEqualTo(DeploymentMode.AUTO);
        assertThat(DeploymentMode.parse("  ")).isEqualTo(DeploymentMode.AUTO);
        assertThat(DeploymentMode.parse("nonsense")).isEqualTo(DeploymentMode.AUTO);
    }

    @Test
    void autoFollowsTheReachabilityProbe() {
        assertThat(DeploymentMode.resolve(DeploymentMode.AUTO, true)).isEqualTo(DeploymentMode.POD);
        assertThat(DeploymentMode.resolve(DeploymentMode.AUTO, false)).isEqualTo(DeploymentMode.STANDALONE);
    }

    @Test
    void explicitModesIgnoreTheProbe() {
        assertThat(DeploymentMode.resolve(DeploymentMode.POD, false)).isEqualTo(DeploymentMode.POD);
        assertThat(DeploymentMode.resolve(DeploymentMode.STANDALONE, true)).isEqualTo(DeploymentMode.STANDALONE);
    }

    @Test
    void onlyPodModeReportsToAController() {
        assertThat(DeploymentMode.POD.reportsToController()).isTrue();
        assertThat(DeploymentMode.STANDALONE.reportsToController()).isFalse();
        assertThat(DeploymentMode.AUTO.reportsToController()).isFalse();
    }

    @Test
    void resolveNeverReturnsAuto() {
        for (DeploymentMode mode : DeploymentMode.values()) {
            assertThat(DeploymentMode.resolve(mode, true)).isNotEqualTo(DeploymentMode.AUTO);
            assertThat(DeploymentMode.resolve(mode, false)).isNotEqualTo(DeploymentMode.AUTO);
        }
    }

    @Test
    void policyRejectsANonsenseDisableThreshold() {
        assertThatThrownBy(() -> new ReportingPolicy(true, 0, 1000L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}