package dev.bedwars.spigot.util;

/**
 * Rolling ticks-per-second estimate. The plugin tick loop calls {@link #tick()}
 * once per server tick; the value is reported in pod heartbeats.
 */
public final class TpsMeter {

    private long windowStartMillis = System.currentTimeMillis();
    private int ticksInWindow;
    private volatile double tps = 20.0;

    public void tick() {
        ticksInWindow++;
        long now = System.currentTimeMillis();
        long elapsed = now - windowStartMillis;
        if (elapsed >= 1000L) {
            tps = ticksInWindow * 1000.0 / elapsed;
            ticksInWindow = 0;
            windowStartMillis = now;
        }
    }

    public double tps() {
        return Math.min(20.0, tps);
    }
}