package dev.bedwars.core.upgrade;

/** The upgrade tracks a team can buy at the upgrade merchant. */
public enum UpgradeType {
    SHARPNESS("Sharpness", 2),
    PROTECTION("Protection", 4),
    MANIAC_MINER("Maniac Miner", 2),
    IRON_FORGE("Iron Forge", 4),
    HEAL_POOL("Heal Pool", 1),
    DRAGON_BUFF("Dragon Buff", 1);

    private final String displayName;
    private final int maxLevel;

    UpgradeType(String displayName, int maxLevel) {
        this.displayName = displayName;
        this.maxLevel = maxLevel;
    }

    public String displayName() {
        return displayName;
    }

    public int maxLevel() {
        return maxLevel;
    }
}