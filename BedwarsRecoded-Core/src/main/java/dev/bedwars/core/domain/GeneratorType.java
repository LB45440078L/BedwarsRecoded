package dev.bedwars.core.domain;

/** Resource a generator produces. */
public enum GeneratorType {
    IRON("Iron", 1.5),
    GOLD("Gold", 3.0),
    DIAMOND("Diamond", 30.0),
    EMERALD("Emerald", 45.0);

    private final String display;
    private final double baseIntervalSeconds;

    GeneratorType(String display, double baseIntervalSeconds) {
        this.display = display;
        this.baseIntervalSeconds = baseIntervalSeconds;
    }

    public String display() {
        return display;
    }

    public double baseIntervalSeconds() {
        return baseIntervalSeconds;
    }
}