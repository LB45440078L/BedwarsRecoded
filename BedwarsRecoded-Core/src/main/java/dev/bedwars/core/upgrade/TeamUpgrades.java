package dev.bedwars.core.upgrade;

import java.util.EnumMap;
import java.util.Map;

/** A team's purchased upgrade levels. Owned by the team, reset with the pod. */
public final class TeamUpgrades {

    private final Map<UpgradeType, Integer> levels = new EnumMap<>(UpgradeType.class);

    public int levelOf(UpgradeType type) {
        return levels.getOrDefault(type, 0);
    }

    public boolean canUpgrade(UpgradeType type) {
        return levelOf(type) < type.maxLevel();
    }

    public void setLevel(UpgradeType type, int level) {
        levels.put(type, Math.min(level, type.maxLevel()));
    }

    public Map<UpgradeType, Integer> snapshot() {
        return Map.copyOf(levels);
    }
}