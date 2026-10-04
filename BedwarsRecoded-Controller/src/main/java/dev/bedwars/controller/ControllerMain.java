package dev.bedwars.controller;

import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.http.WebhookServer;
import dev.bedwars.controller.k8s.Fabric8GameServerSetScaler;
import dev.bedwars.controller.k8s.GameServerSetScaler;
import dev.bedwars.controller.pod.ReadyPodRegistry;
import dev.bedwars.controller.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Entry point for the Bedwars controller. Wires the ready-pod registry, the
 * GameServerSet scaler, the queue manager (with pre-warm) and the HTTP server.
 *
 * <p>Pod scaling is primarily KEDA/Karpenter's job; the controller's own scaling
 * is a pre-warm hint: when a queue request cannot be satisfied, it asks the
 * GameServerSet for one more replica so a pod is booting before players arrive.
 */
public final class ControllerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerMain.class);

    private ControllerMain() {
    }

    public static void main(String[] args) throws Exception {
        ControllerConfig config = ControllerConfig.fromEnv();
        ReadyPodRegistry registry = new ReadyPodRegistry();
        GameServerSetScaler scaler = createScaler(config);

        QueueManager queueManager = new QueueManager(group -> {
            Optional<String> pod = registry.allocate(group);
            if (pod.isPresent()) {
                LOG.info("Allocated ready pod {} for group {}", pod.get(), group);
            } else {
                // Pre-warm: ask for capacity before the next request arrives.
                scaler.scaleUpOne();
            }
            return pod;
        }, config.baseBackoffMillis(), config.maxBackoffMillis());

        WebhookServer server = new WebhookServer(config.httpPort(), queueManager, registry, config, LOG);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Controller shutting down");
            server.stop();
            closeQuietly(scaler);
        }, "shutdown"));

        LOG.info("Bedwars controller started for GameServerSet {}/{}", config.namespace(), config.gameServerSet());
    }

    private static GameServerSetScaler createScaler(ControllerConfig config) {
        try {
            return new Fabric8GameServerSetScaler(
                    config.namespace(), config.gameServerSet(), config.minReplicas(), config.maxReplicas(), LOG);
        } catch (RuntimeException e) {
            LOG.warn("Kubernetes client unavailable; scaling disabled: {}", e.getMessage());
            return new NoOpScaler();
        }
    }

    private static void closeQuietly(Object resource) {
        if (resource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("Failed to close resource", e);
            }
        }
    }

    private static final class NoOpScaler implements GameServerSetScaler {
        @Override
        public int currentReplicas() {
            return 0;
        }

        @Override
        public void scaleTo(int replicas) {
            LOG.debug("Scaling disabled; ignoring scaleTo({})", replicas);
        }

        @Override
        public int scaleUpOne() {
            LOG.debug("Scaling disabled; ignoring scaleUpOne()");
            return 0;
        }
    }
}