package dev.bedwars.core.domain;

/**
 * Upgrade tiers for a generator. Higher tiers shorten the spawn interval and can
 * emit stacks. Diamond/Emerald generators step through these on a fixed schedule;
 * team forges apply to Iron/Gold.
 */
public enum GeneratorTier {
    I(1.0, 1),
    II(0.66, 1),
    III(0.5, 1),
    IV(0.4, 2),
    MAX(0.25, 2);

    private final double intervalMultiplier;
    private final int stackSize;

    GeneratorTier(double intervalMultiplier, int stackSize) {
        this.intervalMultiplier = intervalMultiplier;
        this.stackSize = stackSize;
    }

    public double intervalMultiplier() {
        return intervalMultiplier;
    }

    public int stackSize() {
        return stackSize;
    }

    /** Interval between spawns for a resource at this tier. */
    public double intervalSeconds(GeneratorType type) {
        return type.baseIntervalSeconds() * intervalMultiplier;
    }

    public GeneratorTier next() {
        return switch (this) {
            case I -> II;
            case II -> III;
            case III -> IV;
            case IV, MAX -> MAX;
        };
    }
}