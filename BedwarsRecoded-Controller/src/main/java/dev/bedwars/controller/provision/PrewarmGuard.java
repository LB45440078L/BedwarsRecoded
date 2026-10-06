package dev.bedwars.controller.provision;

/**
 * Decides whether the controller may pre-warm another game server.
 *
 * <p>Without this guard the allocator asked for a new server on <em>every</em> dispatch
 * retry, while a server needs tens of seconds to boot and register. Because the queue
 * retries on a short backoff, a single player's request could start a whole fleet: one
 * lobby queue entry produced 13 containers, bounded only by the configured maximum.
 * A pre-warm counts as in flight until a server registers, so the guard refuses further
 * pre-warms while one is booting.
 */
public final class PrewarmGuard {

    private PrewarmGuard() {
    }

    /**
     * @param currentServers         servers the provisioner has started (registered or not)
     * @param registeredServers      servers that have reported in to the controller
     * @param millisSinceLastPrewarm time since the last pre-warm request
     * @param graceMillis            how long a freshly started server is assumed to be booting
     * @return true when it is safe to pre-warm another server
     */
    public static boolean shouldPrewarm(int currentServers,
                                        int registeredServers,
                                        long millisSinceLastPrewarm,
                                        long graceMillis) {
        // A server that exists but has not reported in yet is a boot in flight.
        if (currentServers > registeredServers) {
            return false;
        }
        // Even before that server becomes visible, do not stampede: one pre-warm per
        // grace window is enough to keep demand ahead of arrivals.
        return millisSinceLastPrewarm >= graceMillis;
    }
}
