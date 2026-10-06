package dev.bedwars.controller.provision;

import dev.bedwars.controller.config.ControllerConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** Factory selection and the config value objects it depends on. */
class ProvisionerFactoryTest {

    private static ControllerConfig configWith(ProvisionerKind kind) {
        ControllerConfig.Provisioning provisioning = new ControllerConfig.Provisioning(
                kind, 2, 20, 25, true, 10, "bedwars-game",
                "bedwars-recoded-game:latest", "1536m", "solo", "http://host.docker.internal:8080",
                "bedwars_default");
        return new ControllerConfig("bedwars", "bedwars-solo", 8080, 2, 500, 10_000,
                provisioning, new ControllerConfig.Security(""));
    }

    @Test
    void noneKindYieldsTheNoopProvisioner() {
        ServerProvisioner provisioner = ProvisionerFactory.create(configWith(ProvisionerKind.NONE));
        assertThat(provisioner).isInstanceOf(NoopProvisioner.class);
        assertThat(provisioner.kind()).isEqualTo(ProvisionerKind.NONE);
        assertThat(provisioner.currentServers()).isZero();
        assertThat(provisioner.totalGameCapacity()).isZero();
    }

    @Test
    void kindParsingIsForgivingAndDefaultsToKubernetes() {
        assertThat(ProvisionerKind.parse("docker")).isEqualTo(ProvisionerKind.DOCKER);
        assertThat(ProvisionerKind.parse(" Kubernetes ")).isEqualTo(ProvisionerKind.KUBERNETES);
        assertThat(ProvisionerKind.parse("none")).isEqualTo(ProvisionerKind.NONE);
        assertThat(ProvisionerKind.parse("bogus")).isEqualTo(ProvisionerKind.KUBERNETES);
        assertThat(ProvisionerKind.parse(null)).isEqualTo(ProvisionerKind.KUBERNETES);
        assertThat(ProvisionerKind.parse("")).isEqualTo(ProvisionerKind.KUBERNETES);
    }

    @Test
    void noopProvisionerIsInertAndSafe() {
        NoopProvisioner provisioner = new NoopProvisioner(LoggerFactory.getLogger("test"));
        assertThat(provisioner.scaleUpOne()).isZero();
        provisioner.scaleTo(10); // must not throw
        assertThat(provisioner.describe()).contains("disabled");
    }

    @Test
    void idleWindowIsExpressedInMillis() {
        assertThat(configWith(ProvisionerKind.NONE).provisioning().idleMillis()).isEqualTo(600_000L);
    }

    @Test
    void securityTogglesOnAConfiguredToken() {
        assertThat(new ControllerConfig.Security("").enabled()).isFalse();
        assertThat(new ControllerConfig.Security("   ").enabled()).isFalse();
        assertThat(new ControllerConfig.Security("abc").enabled()).isTrue();
    }
}
