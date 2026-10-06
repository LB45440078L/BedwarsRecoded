package dev.bedwars.controller;

import dev.bedwars.controller.config.ControllerConfig;
import dev.bedwars.controller.http.WebhookServer;
import dev.bedwars.controller.pod.ServerRegistry;
import dev.bedwars.controller.provision.PrewarmGuard;
import dev.bedwars.controller.provision.ProvisionerFactory;
import dev.bedwars.controller.provision.ScaleDownPolicy;
import dev.bedwars.controller.provision.ServerProvisioner;
import dev.bedwars.controller.queue.QueueManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Entry point for the Bedwars controller.
 *
 * <p>It wires the ready-pod registry, the {@link ServerProvisioner} (Kubernetes or
 * Docker, chosen by config), the queue manager with pre-warm, the idle scale-down
 * loop and the HTTP server. The controller never contains gameplay logic; it only
 * moves capacity and players around.
 */
public final class ControllerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ControllerMain.class);
    private static final long SCALE_DOWN_TICK_SECONDS = 60L;
    /** How long a freshly started server is assumed to still be booting. */
    private static final long PREWARM_GRACE_MILLIS = 30_000L;

    private ControllerMain() {
    }

    public static void main(String[] args) throws Exception {
        ControllerConfig config = ControllerConfig.fromEnv();
        ServerRegistry registry = new ServerRegistry();
        ServerProvisioner provisioner = ProvisionerFactory.create(config);

        AtomicLong lastPrewarmAt = new AtomicLong(0L);
        QueueManager queueManager = new QueueManager(group -> {
            Optional<String> pod = registry.allocate(group);
            if (pod.isPresent()) {
                LOG.info("Allocated ready pod {} for group {}", pod.get(), group);
            } else if (PrewarmGuard.shouldPrewarm(provisioner.currentServers(), registry.serverCount(),
                    System.currentTimeMillis() - lastPrewarmAt.get(), PREWARM_GRACE_MILLIS)) {
                // Pre-warm at most one server per boot window. Retrying the dispatch must
                // not start a container per attempt: a server that is still booting is
                // already the capacity this request is waiting for.
                lastPrewarmAt.set(System.currentTimeMillis());
                LOG.info("Pre-warming a game server for group {}", group);
                provisioner.scaleUpOne();
            }
            return pod;
        }, config.baseBackoffMillis(), config.maxBackoffMillis());

        WebhookServer server = new WebhookServer(config.httpPort(), queueManager, registry, config, provisioner, LOG);
        server.start();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scale-down");
            thread.setDaemon(true);
            return thread;
        });
        startScaleDownLoop(config, queueManager, registry, provisioner, scheduler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Controller shutting down");
            scheduler.shutdownNow();
            server.stop();
            provisioner.close();
        }, "shutdown"));

        LOG.info("Bedwars controller started ({})", provisioner.describe());
    }

    /**
     * Reclaims idle, dynamically provisioned servers. The decision itself lives in
     * {@link ScaleDownPolicy} so it is unit-tested; this method only feeds it state
     * and applies the outcome. Reserved/active servers are never candidates because
     * an allocated pod leaves the ready pool the moment it is dispatched.
     */
    private static void startScaleDownLoop(ControllerConfig config, QueueManager queueManager,
                                           ServerRegistry registry, ServerProvisioner provisioner,
                                           ScheduledExecutorService scheduler) {
        ControllerConfig.Provisioning p = config.provisioning();
        ScaleDownPolicy policy = new ScaleDownPolicy(p.scaleDownEnabled(), p.minServers(), p.idleMillis());
        AtomicLong idleSince = new AtomicLong(System.currentTimeMillis());

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int depth = queueManager.totalDepth();
                long now = System.currentTimeMillis();
                if (depth > 0) {
                    idleSince.set(now);
                    return;
                }
                int idle = registry.idleServers();
                int current = provisioner.currentServers();
                ScaleDownPolicy.Decision decision = policy.evaluate(current, idle, depth, now - idleSince.get());
                if (decision.scaleDown()) {
                    int target = policy.nextTarget(current);
                    LOG.info("Scaling down {} -> {} ({})", current, target, decision.reason());
                    provisioner.scaleTo(target);
                }
            } catch (RuntimeException e) {
                LOG.warn("Scale-down tick failed: {}", e.getMessage());
            }
        }, SCALE_DOWN_TICK_SECONDS, SCALE_DOWN_TICK_SECONDS, TimeUnit.SECONDS);
    }
}
