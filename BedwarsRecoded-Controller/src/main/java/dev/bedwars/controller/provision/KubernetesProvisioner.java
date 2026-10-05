package dev.bedwars.controller.provision;

import dev.bedwars.controller.k8s.GameServerSetScaler;
import org.slf4j.Logger;

/**
 * Kubernetes provisioner: delegates to a {@link GameServerSetScaler} (OpenKruise
 * {@code GameServerSet}). Kept as a thin adapter so the K8s client stays behind the
 * {@link ServerProvisioner} seam and can be swapped for Docker with a config value.
 */
public final class KubernetesProvisioner implements ServerProvisioner {

    private final GameServerSetScaler scaler;
    private final String namespace;
    private final String gameServerSet;
    private final int minServers;
    private final int maxServers;
    private final int gamesPerServer;
    private final Logger log;

    public KubernetesProvisioner(GameServerSetScaler scaler, String namespace, String gameServerSet,
                                 int minServers, int maxServers, int gamesPerServer, Logger log) {
        this.scaler = scaler;
        this.namespace = namespace;
        this.gameServerSet = gameServerSet;
        this.minServers = minServers;
        this.maxServers = Math.max(minServers, maxServers);
        this.gamesPerServer = Math.max(1, gamesPerServer);
        this.log = log;
    }

    @Override
    public ProvisionerKind kind() {
        return ProvisionerKind.KUBERNETES;
    }

    @Override
    public int currentServers() {
        return scaler.currentReplicas();
    }

    @Override
    public int minimumServers() {
        return minServers;
    }

    @Override
    public int maximumServers() {
        return maxServers;
    }

    @Override
    public int gamesPerServer() {
        return gamesPerServer;
    }

    @Override
    public int scaleUpOne() {
        return scaler.scaleUpOne();
    }

    @Override
    public void scaleTo(int servers) {
        scaler.scaleTo(servers);
    }

    @Override
    public String describe() {
        return "kubernetes GameServerSet " + namespace + "/" + gameServerSet
                + " servers[" + minServers + ".." + maxServers + "] gamesPerServer=" + gamesPerServer;
    }

    @Override
    public void close() {
        if (scaler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("Failed to close the Kubernetes scaler", e);
            }
        }
    }
}
