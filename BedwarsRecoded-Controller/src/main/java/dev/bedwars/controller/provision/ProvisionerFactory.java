package dev.bedwars.controller.provision;

import dev.bedwars.controller.config.ControllerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds the {@link ServerProvisioner} the controller should use, from configuration.
 * This is the only place that knows the concrete backend classes, which is what keeps
 * the rest of the controller infrastructure-agnostic.
 *
 * <p>Every failure mode degrades to {@link NoopProvisioner} rather than a crash: a
 * controller that cannot reach its infrastructure still serves the queue and reports
 * "no capacity", which is far better than refusing to start.
 */
public final class ProvisionerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(ProvisionerFactory.class);

    private ProvisionerFactory() {
    }

    public static ServerProvisioner create(ControllerConfig config) {
        ControllerConfig.Provisioning p = config.provisioning();
        ServerProvisioner provisioner = switch (p.kind()) {
            case NONE -> new NoopProvisioner(LOG);
            case DOCKER -> docker(config);
            case KUBERNETES -> kubernetes(config);
        };
        LOG.info("Server provisioner: {}", provisioner.describe());
        return provisioner;
    }

    private static ServerProvisioner kubernetes(ControllerConfig config) {
        ControllerConfig.Provisioning p = config.provisioning();
        try {
            var scaler = new dev.bedwars.controller.k8s.Fabric8GameServerSetScaler(
                    config.namespace(), config.gameServerSet(), p.minServers(), p.maxServers(), LOG);
            return new KubernetesProvisioner(scaler, config.namespace(), config.gameServerSet(),
                    p.minServers(), p.maxServers(), p.gamesPerServer(), LOG);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Kubernetes client unavailable ({}); scaling disabled", e.getMessage());
            return new NoopProvisioner(LOG);
        }
    }

    private static ServerProvisioner docker(ControllerConfig config) {
        ControllerConfig.Provisioning p = config.provisioning();
        CommandRunner runner = new ProcessCommandRunner(30_000L);
        var docker = new DockerProvisioner(runner, "docker", p.serverPrefix(), p.dockerImage(),
                p.containerMemory(), p.gamesPerServer(), p.minServers(), p.maxServers(),
                config.provisioning().arenaGroup(), config.controllerUrlForGameServers(),
                p.dockerNetwork(), List.of(), LOG);
        if (!docker.available()) {
            LOG.warn("Docker daemon not reachable; provisioning disabled "
                    + "(start Docker or set BEDWARS_PROVISIONER=NONE)");
            docker.close();
            return new NoopProvisioner(LOG);
        }
        return docker;
    }
}
