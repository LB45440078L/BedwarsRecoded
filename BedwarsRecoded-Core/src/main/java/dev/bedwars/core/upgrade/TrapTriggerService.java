package dev.bedwars.core.upgrade;

import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides when a team's armed trap fires. A trap fires when an enemy enters the
 * team's base (within {@code baseRadius} of the bed), subject to a per-team
 * cooldown so a single intruder cannot drain the whole queue instantly.
 *
 * <p>Pure logic: the caller supplies the intruder position and timestamp and is
 * responsible for confirming the intruder is not on the team.
 */
public final class TrapTriggerService {

    private final double baseRadius;
    private final long cooldownMillis;
    private final Map<String, Long> lastTrigger = new ConcurrentHashMap<>();

    public TrapTriggerService(double baseRadius, long cooldownMillis) {
        this.baseRadius = baseRadius;
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * @return the trap that fired (and removes it from the queue), or empty
     */
    public Optional<TrapType> checkTrigger(Team team, Vec3 intruderPosition, long nowMillis) {
        if (team.traps().size() == 0) {
            return Optional.empty();
        }
        if (!intruderPosition.isWithin(team.bed().position(), baseRadius)) {
            return Optional.empty();
        }
        Long last = lastTrigger.get(team.id());
        if (last != null && nowMillis - last < cooldownMillis) {
            return Optional.empty();
        }
        Optional<TrapType> fired = team.traps().triggerNext();
        fired.ifPresent(trap -> lastTrigger.put(team.id(), nowMillis));
        return fired;
    }

    public void reset(String teamId) {
        lastTrigger.remove(teamId);
    }
}