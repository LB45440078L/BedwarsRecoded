package dev.bedwars.core.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable configuration for an arena group (solo / doubles / 4s ...). Loaded
 * per-group from YAML; a pod hosts exactly one group.
 *
 * @param voidYThreshold        below this Y a falling player is killed by the void
 * @param islandRadius          build/break allowed within this radius of an island
 * @param bedProtectionRadius   block placement forbidden within this radius of a bed
 * @param suddenDeathAfterSecs  seconds of RUNNING before sudden death; 0 disables
 * @param waitingRoom           where players are held <em>before</em> the match starts.
 *                              The original plugin kept this per arena as
 *                              {@code map-lobby-spawn} (Glacier: 0, 118.05, 0 -- a platform
 *                              above the map, well above the y=81 team spawns). Without it
 *                              a joining player was dropped at their island the instant they
 *                              connected -- before the world was ready -- and fell through
 *                              unloaded ground into the void. Empty means "not configured":
 *                              the server falls back to the world spawn and says so, rather
 *                              than guessing a coordinate.
 * @param minPlayers            players required before the countdown starts; 0 means
 *                              "one per team" ({@link #effectiveMinPlayers()})
 * @param waitingRoomPlatform   place a small safety floor under the waiting room if there is
 *                              nothing solid there, so nobody falls out of the world
 */
public record ArenaGroup(
        String id,
        int teamCount,
        int playersPerTeam,
        int countdownSeconds,
        int suddenDeathAfterSecs,
        double voidYThreshold,
        double islandRadius,
        double bedProtectionRadius,
        List<GeneratorType> teamGenerators,
        Optional<Vec3> waitingRoom,
        int minPlayers,
        boolean waitingRoomPlatform
) {
    public ArenaGroup {
        Objects.requireNonNull(id, "id");
        teamGenerators = List.copyOf(teamGenerators);
        waitingRoom = waitingRoom == null ? Optional.empty() : waitingRoom;
        if (teamCount < 1 || playersPerTeam < 1) {
            throw new IllegalArgumentException("teamCount and playersPerTeam must be positive");
        }
    }

    /** The pre-waiting-room shape, so callers that do not care keep compiling. */
    public ArenaGroup(String id, int teamCount, int playersPerTeam, int countdownSeconds,
                      int suddenDeathAfterSecs, double voidYThreshold, double islandRadius,
                      double bedProtectionRadius, List<GeneratorType> teamGenerators) {
        this(id, teamCount, playersPerTeam, countdownSeconds, suddenDeathAfterSecs,
                voidYThreshold, islandRadius, bedProtectionRadius, teamGenerators,
                Optional.empty(), 0, true);
    }

    public int capacity() {
        return teamCount * playersPerTeam;
    }

    /**
     * How many players must be present before the match may start.
     *
     * <p>The countdown used to require {@code playerCount >= teamCount}, which is a
     * different (and wrong) question: it says nothing about whether a playable match can
     * happen, and it fired the countdown the moment one player per team existed even when
     * the arena was configured for more. One player per team is the floor.
     */
    public int effectiveMinPlayers() {
        int floor = minPlayers > 0 ? minPlayers : teamCount;
        return Math.max(1, Math.min(floor, capacity()));
    }

    /** True when the arena names a waiting room, so nobody has to fall back. */
    public boolean hasWaitingRoom() {
        return waitingRoom.isPresent();
    }
}
