package dev.bedwars.controller.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;

/**
 * Fabric8-backed scaler. Reads the GameServerSet, mutates only {@code replicas},
 * and updates. Uses OpenKruise's GameServerSet custom resource.
 */
public final class Fabric8GameServerSetScaler implements GameServerSetScaler, AutoCloseable {

    private final KubernetesClient client;
    private final String namespace;
    private final String name;
    private final int min;
    private final int max;
    private final Logger log;

    public Fabric8GameServerSetScaler(String namespace, String name, int min, int max, Logger log) {
        this(new KubernetesClientBuilder().build(), namespace, name, min, max, log);
    }

    Fabric8GameServerSetScaler(KubernetesClient client, String namespace, String name, int min, int max, Logger log) {
        this.client = client;
        this.namespace = namespace;
        this.name = name;
        this.min = min;
        this.max = max;
        this.log = log;
    }

    @Override
    public int currentReplicas() {
        GameServerSet set = get();
        return set == null || set.getSpec() == null || set.getSpec().getReplicas() == null
                ? 0 : set.getSpec().getReplicas();
    }

    @Override
    public void scaleTo(int replicas) {
        int target = Math.clamp(replicas, min, max);
        GameServerSet set = get();
        if (set == null) {
            log.warn("GameServerSet {}/{} not found; cannot scale", namespace, name);
            return;
        }
        set.getSpec().setReplicas(target);
        client.resource(set).inNamespace(namespace).update();
        log.info("Scaled GameServerSet {}/{} to {} replicas", namespace, name, target);
    }

    @Override
    public int scaleUpOne() {
        int target = Math.clamp(currentReplicas() + 1, min, max);
        scaleTo(target);
        return target;
    }

    private GameServerSet get() {
        return client.resources(GameServerSet.class).inNamespace(namespace).withName(name).get();
    }

    @Override
    public void close() {
        client.close();
    }
}