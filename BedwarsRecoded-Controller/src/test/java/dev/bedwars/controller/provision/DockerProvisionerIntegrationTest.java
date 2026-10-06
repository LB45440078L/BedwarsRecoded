package dev.bedwars.controller.provision;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a <b>real</b> Docker daemon: provisions a container, verifies it is running,
 * then reclaims it and asserts nothing is left behind. Self-skips (rather than fails)
 * when no daemon is available, so the suite still runs on a machine without Docker.
 */
class DockerProvisionerIntegrationTest {

    private static boolean dockerUp() {
        // Generous timeout: a busy host (other builds/tests running) can take well over
        // 15s to answer `docker version`, and a timeout here silently SKIPS the whole
        // integration test, which reads as "not verified" when the daemon is fine.
        CommandRunner probe = new ProcessCommandRunner(45_000);
        try {
            return probe.run(List.of("docker", "version", "--format", "{{.Server.Version}}")).ok();
        } finally {
            if (probe instanceof AutoCloseable closeable) {
                closeQuietly(closeable);
            }
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Test
    void provisionsHealthChecksAndReclaimsARealContainer() {
        Assumptions.assumeTrue(dockerUp(), "docker daemon not reachable");

        String prefix = "bedwars-it-" + UUID.randomUUID().toString().substring(0, 8);
        ProcessCommandRunner runner = new ProcessCommandRunner(120_000);
        DockerProvisioner provisioner = new DockerProvisioner(runner, "docker", prefix, "alpine:latest",
                "256m", 25, 0, 2, "solo", "http://controller:8080", "",
                List.of("sleep", "3600"), LoggerFactory.getLogger("integration"));

        try {
            assertThat(provisioner.available()).as("docker daemon reachable").isTrue();

            assertThat(provisioner.scaleUpOne()).isEqualTo(1);
            List<String> servers = provisioner.allServers();
            assertThat(servers).as("one container provisioned").hasSize(1);
            String name = servers.getFirst();

            assertThat(provisioner.isHealthy(name)).as("container is running").isTrue();

            provisioner.scaleTo(0);
            assertThat(provisioner.runningServers()).as("nothing running after scale-down").isEmpty();
            assertThat(provisioner.allServers()).as("no orphaned containers").isEmpty();
        } finally {
            for (String leftover : provisioner.allServers()) {
                provisioner.stopServer(leftover);
            }
            closeQuietly(runner);
        }
    }
}
