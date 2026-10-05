package dev.bedwars.controller.provision;

/**
 * Decides whether a dynamically provisioned server may be reclaimed. Kept pure and
 * separate from the scheduler so the rule is unit-tested directly instead of through
 * timers.
 *
 * <p>A server is only reclaimed when all of the following hold:
 * <ul>
 *   <li>scaling down is enabled;</li>
 *   <li>nobody is waiting in any queue;</li>
 *   <li>there is a <em>surplus idle</em> server to give back &mdash; a server with an
 *       active game is never in the ready pool, so {@code readyServers} counts only
 *       idle capacity;</li>
 *   <li>the fleet is above the configured minimum; and</li>
 *   <li>the fleet has been continuously idle for at least the configured window.</li>
 * </ul>
 * This is what guarantees "never terminate an active game".
 */
public final class ScaleDownPolicy {

    /** Outcome plus the reason, so the controller can log a single clear line. */
    public record Decision(boolean scaleDown, String reason) {
    }

    private final boolean enabled;
    private final int minServers;
    private final long idleMillis;

    public ScaleDownPolicy(boolean enabled, int minServers, long idleMillis) {
        this.enabled = enabled;
        this.minServers = Math.max(0, minServers);
        this.idleMillis = Math.max(0L, idleMillis);
    }

    public Decision evaluate(int currentServers, int readyServers, int queueDepth, long idleForMillis) {
        if (!enabled) {
            return new Decision(false, "scale-down disabled");
        }
        if (queueDepth > 0) {
            return new Decision(false, "players are waiting (" + queueDepth + ")");
        }
        if (readyServers <= 0) {
            return new Decision(false, "no idle server to reclaim");
        }
        if (currentServers <= minServers) {
            return new Decision(false, "at minimum capacity (" + minServers + ")");
        }
        if (idleForMillis < idleMillis) {
            return new Decision(false, "idle " + idleForMillis + "ms < " + idleMillis + "ms");
        }
        return new Decision(true, "idle for " + idleForMillis + "ms with " + readyServers + " idle servers");
    }

    /** The server count one scale-down step should converge on. Never below the minimum. */
    public int nextTarget(int currentServers) {
        return Math.max(minServers, currentServers - 1);
    }
}
