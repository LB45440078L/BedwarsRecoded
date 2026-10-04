package dev.bedwars.core.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-player state within a single match. Never persists and never outlives the
 * {@link Game} that owns it.
 */
public final class PlayerSession {

    private final UUID uuid;
    private final String username;
    private String teamId;
    private PlayerState state = PlayerState.ALIVE;
    private int kills;
    private int finalKills;
    private int deaths;
    private int finalDeaths;
    private int bedsBroken;
    private int bedsLost;
    private int killstreak;
    private long respawnAtMillis = -1L;

    public PlayerSession(UUID uuid, String username) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.username = Objects.requireNonNull(username, "username");
    }

    public UUID uuid() {
        return uuid;
    }

    public String username() {
        return username;
    }

    public Optional<String> teamId() {
        return Optional.ofNullable(teamId);
    }

    public void assignTeam(String teamId) {
        this.teamId = Objects.requireNonNull(teamId, "teamId");
    }

    public PlayerState state() {
        return state;
    }

    public void setState(PlayerState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    public boolean isActive() {
        return state == PlayerState.ALIVE || state == PlayerState.RESPAWNING;
    }

    public void addKill(boolean finale) {
        if (finale) {
            finalKills++;
        } else {
            kills++;
        }
        killstreak++;
    }

    public void addDeath(boolean finale) {
        if (finale) {
            finalDeaths++;
        } else {
            deaths++;
        }
        killstreak = 0;
    }

    public void addBedBroken() {
        bedsBroken++;
    }

    public void addBedLost() {
        bedsLost++;
    }

    public int kills() {
        return kills;
    }

    public int finalKills() {
        return finalKills;
    }

    public int deaths() {
        return deaths;
    }

    public int finalDeaths() {
        return finalDeaths;
    }

    public int bedsBroken() {
        return bedsBroken;
    }

    public int bedsLost() {
        return bedsLost;
    }

    public int killstreak() {
        return killstreak;
    }

    public long respawnAtMillis() {
        return respawnAtMillis;
    }

    public void scheduleRespawn(long atMillis) {
        this.respawnAtMillis = atMillis;
    }

    public long experienceGained(boolean winner) {
        return kills * 10L + finalKills * 25L + bedsBroken * 50L + (winner ? 100L : 0L);
    }
}