package dev.bedwars.controller.provision;

import org.slf4j.Logger;

/**
 * A provisioner that provisions nothing. Used when the controller runs without
 * infrastructure access (local development, unit tests, a read-only controller).
 * It never throws, so the queue logic degrades to "no capacity" instead of failing.
 */
public final class NoopProvisioner implements ServerProvisioner {

    private final Logger log;

    public NoopProvisioner(Logger log) {
        this.log = log;
    }

    @Override
    public ProvisionerKind kind() {
        return ProvisionerKind.NONE;
    }

    @Override
    public int currentServers() {
        return 0;
    }

    @Override
    public int minimumServers() {
        return 0;
    }

    @Override
    public int maximumServers() {
        return 0;
    }

    @Override
    public int gamesPerServer() {
        return 1;
    }

    @Override
    public int scaleUpOne() {
        log.debug("Provisioning disabled; ignoring scaleUpOne()");
        return 0;
    }

    @Override
    public void scaleTo(int servers) {
        log.debug("Provisioning disabled; ignoring scaleTo({})", servers);
    }

    @Override
    public String describe() {
        return "none (scaling disabled)";
    }
}
