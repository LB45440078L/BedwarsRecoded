package dev.bedwars.core.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Attributes kills to the most recent damager within a window, and tracks
 * killstreaks. Deterministic: the caller supplies timestamps.
 */
public final class KillTracker {

    private record Damage(UUID attacker, long atMillis) {
    }

    private final Map<UUID, Damage> lastDamage = new HashMap<>();
    private final Map<UUID, Integer> streaks = new HashMap<>();
    private final long attributionWindowMillis;

    public KillTracker(long attributionWindowMillis) {
        this.attributionWindowMillis = attributionWindowMillis;
    }

    public void recordDamage(UUID victim, UUID attacker, long atMillis) {
        if (victim.equals(attacker)) {
            return;
        }
        lastDamage.put(victim, new Damage(attacker, atMillis));
    }

    /** The attacker to credit for {@code victim}'s death, if within the window. */
    public Optional<UUID> killerOf(UUID victim, long nowMillis) {
        Damage d = lastDamage.get(victim);
        if (d == null || nowMillis - d.atMillis() > attributionWindowMillis) {
            return Optional.empty();
        }
        return Optional.of(d.attacker());
    }

    public void onKill(UUID killer) {
        streaks.merge(killer, 1, Integer::sum);
    }

    public void onDeath(UUID victim) {
        streaks.remove(victim);
        lastDamage.remove(victim);
    }

    public int streakOf(UUID player) {
        return streaks.getOrDefault(player, 0);
    }

    public void clear() {
        lastDamage.clear();
        streaks.clear();
    }
}