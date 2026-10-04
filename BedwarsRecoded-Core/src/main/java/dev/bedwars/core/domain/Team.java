package dev.bedwars.core.domain;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A team within a match: colour, roster, and its bed. */
public final class Team {

    private final String id;
    private final TeamColor color;
    private final Set<UUID> members = new LinkedHashSet<>();
    private final Bed bed;
    private final int maxSize;
    private boolean eliminated;

    public Team(String id, TeamColor color, Bed bed, int maxSize) {
        this.id = Objects.requireNonNull(id, "id");
        this.color = Objects.requireNonNull(color, "color");
        this.bed = Objects.requireNonNull(bed, "bed");
        this.maxSize = maxSize;
    }

    public String id() {
        return id;
    }

    public TeamColor color() {
        return color;
    }

    public Bed bed() {
        return bed;
    }

    public int maxSize() {
        return maxSize;
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    public boolean isFull() {
        return members.size() >= maxSize;
    }

    public boolean isEliminated() {
        return eliminated;
    }

    public boolean addMember(UUID uuid) {
        if (isFull() || eliminated) {
            return false;
        }
        return members.add(uuid);
    }

    public boolean removeMember(UUID uuid) {
        return members.remove(uuid);
    }

    public boolean hasMember(UUID uuid) {
        return members.contains(uuid);
    }

    /** Marks the team out. Idempotent. */
    public void eliminate() {
        this.eliminated = true;
    }
}