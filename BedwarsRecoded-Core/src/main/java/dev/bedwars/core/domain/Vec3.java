package dev.bedwars.core.domain;

/** Immutable 3D point. Keeps Core free of Bukkit's {@code Location}. */
public record Vec3(double x, double y, double z) {

    public double distanceSq(Vec3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3 other) {
        return Math.sqrt(distanceSq(other));
    }

    public boolean isWithin(Vec3 origin, double radius) {
        return distanceSq(origin) <= radius * radius;
    }
}