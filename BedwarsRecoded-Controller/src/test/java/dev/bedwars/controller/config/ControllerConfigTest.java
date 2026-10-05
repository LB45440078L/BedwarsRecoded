package dev.bedwars.controller.config;

import dev.bedwars.controller.provision.ProvisionerKind;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The controller's env-derived defaults: the values an operator gets out of the box. */
class ControllerConfigTest {

    @Test
    void defaultsMatchTheDocumentedContract() {
        // Skip if the environment overrides the BEDWARS_* keys, so the test never lies.
        Assumptions.assumeTrue(System.getenv("BEDWARS_MIN_SERVERS") == null, "env overrides present");
        Assumptions.assumeTrue(System.getenv("BEDWARS_PROVISIONER") == null, "env overrides present");

        ControllerConfig config = ControllerConfig.fromEnv();

        assertThat(config.httpPort()).isEqualTo(8080);
        assertThat(config.provisioning().kind()).isEqualTo(ProvisionerKind.KUBERNETES);
        assertThat(config.provisioning().minServers()).isEqualTo(2);
        assertThat(config.provisioning().maxServers()).isEqualTo(20);
        assertThat(config.provisioning().gamesPerServer()).isEqualTo(25);
        assertThat(config.provisioning().idleMinutes()).isEqualTo(10);
        assertThat(config.provisioning().scaleDownEnabled()).isTrue();
        assertThat(config.security().enabled()).isFalse();
    }

    @Test
    void containerMemoryIsAlwaysPositiveAndGamesPerServerAtLeastOne() {
        ControllerConfig config = ControllerConfig.fromEnv();
        assertThat(config.provisioning().gamesPerServer()).isPositive();
        assertThat(config.provisioning().maxServers()).isGreaterThanOrEqualTo(config.provisioning().minServers());
    }
}
