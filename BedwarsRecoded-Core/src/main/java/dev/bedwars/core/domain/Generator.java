package dev.bedwars.core.domain;

import java.util.Objects;

/**
 * A resource generator. Time is injected via {@link #tick(long)} so the class is
 * fully deterministic and unit-testable without a scheduler.
 */
public final class Generator {

    private final String id;
    private final GeneratorType type;
    private final Vec3 position;
    private GeneratorTier tier;
    private long nextSpawnAtMillis;

    public Generator(String id, GeneratorType type, GeneratorTier tier, Vec3 position, long startAtMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.tier = Objects.requireNonNull(tier, "tier");
        this.position = Objects.requireNonNull(position, "position");
        this.nextSpawnAtMillis = startAtMillis + intervalMillis();
    }

    public String id() {
        return id;
    }

    public GeneratorType type() {
        return type;
    }

    public GeneratorTier tier() {
        return tier;
    }

    public Vec3 position() {
        return position;
    }

    public int itemsPerSpawn() {
        return tier.stackSize();
    }

    public long intervalMillis() {
        return Math.round(tier.intervalSeconds(type) * 1000.0);
    }

    /**
     * Advances the generator to {@code now}, returning how many spawn cycles were
     * due (each cycle yields {@link #itemsPerSpawn()} items). Catches up if the
     * server stalled, but never returns unbounded work for a long pause.
     */
    public int tick(long nowMillis) {
        if (nowMillis < nextSpawnAtMillis) {
            return 0;
        }
        long interval = intervalMillis();
        int cycles = 0;
        while (nextSpawnAtMillis <= nowMillis && cycles < 64) {
            nextSpawnAtMillis += interval;
            cycles++;
        }
        if (nextSpawnAtMillis <= nowMillis) {
            nextSpawnAtMillis = nowMillis + interval;
        }
        return cycles;
    }

    /** Change tier and reschedule from {@code now}. */
    public void setTier(GeneratorTier tier, long nowMillis) {
        this.tier = Objects.requireNonNull(tier, "tier");
        this.nextSpawnAtMillis = nowMillis + intervalMillis();
    }
}