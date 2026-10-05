package dev.bedwars.core.reporting;

/**
 * Decides whether controller reports should still be attempted, and how often a
 * failure may be logged.
 *
 * <p>Why this exists: a plugin running on a plain server (or with a controller that
 * is down) used to log a failure on <em>every</em> report — a heartbeat every 15
 * seconds plus ready/started/ended — which floods the console with
 * {@code ConnectException} noise for a condition the operator cannot fix from there.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>the first failure is logged immediately (with the reason);</li>
 *   <li>later failures are logged at most once per {@code failureLogInterval};</li>
 *   <li>after {@code disableAfterFailures} consecutive failures reporting is
 *       disabled for the life of the server, with one clear final message.</li>
 * </ul>
 * A single success resets the failure count.
 */
public final class ReportingPolicy {

    public static final int DEFAULT_DISABLE_AFTER_FAILURES = 5;
    public static final long DEFAULT_FAILURE_LOG_INTERVAL_MILLIS = 300_000L;

    private final boolean configured;
    private final int disableAfterFailures;
    private final long failureLogIntervalMillis;

    private int consecutiveFailures;
    private long nextFailureLogAtMillis;
    private boolean autoDisabled;

    public ReportingPolicy(boolean configured) {
        this(configured, DEFAULT_DISABLE_AFTER_FAILURES, DEFAULT_FAILURE_LOG_INTERVAL_MILLIS);
    }

    public ReportingPolicy(boolean configured, int disableAfterFailures, long failureLogIntervalMillis) {
        if (disableAfterFailures < 1) {
            throw new IllegalArgumentException("disableAfterFailures must be >= 1");
        }
        this.configured = configured;
        this.disableAfterFailures = disableAfterFailures;
        this.failureLogIntervalMillis = Math.max(0L, failureLogIntervalMillis);
    }

    /** True when reports should actually be sent right now. */
    public boolean shouldSend() {
        return configured && !autoDisabled;
    }

    /** True when the operator asked for reporting but it was switched off by failures. */
    public boolean autoDisabled() {
        return autoDisabled;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    /** @return true when this failure should be written to the log. */
    public boolean shouldLogFailure(long nowMillis) {
        return nowMillis >= nextFailureLogAtMillis;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
        nextFailureLogAtMillis = 0L;
    }

    /** Records a failed report; returns true when this failure was the disabling one. */
    public boolean recordFailure(long nowMillis) {
        consecutiveFailures++;
        if (consecutiveFailures >= disableAfterFailures) {
            boolean firstTime = !autoDisabled;
            autoDisabled = true;
            nextFailureLogAtMillis = nowMillis + failureLogIntervalMillis;
            return firstTime;
        }
        nextFailureLogAtMillis = nowMillis + failureLogIntervalMillis;
        return false;
    }

    /** Human-readable reason for the startup summary. */
    public String describe() {
        if (!configured) {
            return "disabled by configuration";
        }
        if (autoDisabled) {
            return "disabled after " + consecutiveFailures + " consecutive failures";
        }
        return "enabled";
    }
}