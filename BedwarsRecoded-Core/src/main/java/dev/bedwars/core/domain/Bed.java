package dev.bedwars.core.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A team's bed. Once destroyed the team can no longer respawn.
 * Not thread-safe by design: only ever mutated on the game's owning thread.
 */
public final class Bed {

    private final String teamId;
    private final Vec3 position;
    private final double protectionRadius;
    private boolean destroyed;
    private UUID destroyedBy;
    private long destroyedAtMillis = -1L;

    public Bed(String teamId, Vec3 position, double protectionRadius) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
        this.position = Objects.requireNonNull(position, "position");
        this.protectionRadius = protectionRadius;
    }

    public String teamId() {
        return teamId;
    }

    public Vec3 position() {
        return position;
    }

    public double protectionRadius() {
        return protectionRadius;
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public Optional<UUID> destroyedBy() {
        return Optional.ofNullable(destroyedBy);
    }

    public long destroyedAtMillis() {
        return destroyedAtMillis;
    }

    /**
     * @return true if this call destroyed the bed (first time only)
     */
    public boolean destroy(UUID breaker, long atMillis) {
        if (destroyed) {
            return false;
        }
        this.destroyed = true;
        this.destroyedBy = Objects.requireNonNull(breaker, "breaker");
        this.destroyedAtMillis = atMillis;
        return true;
    }

    /**
     * System-initiated destruction (e.g. sudden death) with no player credit.
     *
     * @return true if this call destroyed the bed
     */
    public boolean forceDestroy() {
        if (destroyed) {
            return false;
        }
        this.destroyed = true;
        this.destroyedBy = null;
        return true;
    }
}