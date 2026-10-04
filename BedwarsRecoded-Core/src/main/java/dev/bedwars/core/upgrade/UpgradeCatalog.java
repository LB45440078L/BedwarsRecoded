package dev.bedwars.core.upgrade;

import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.Price;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The upgrade tiers available, per type. Defaults mirror classic Bedwars pricing;
 * a pod may override them from its arena-group config.
 */
public final class UpgradeCatalog {

    private final Map<UpgradeType, List<UpgradeTier>> tiers = new EnumMap<>(UpgradeType.class);

    public UpgradeCatalog(Map<UpgradeType, List<UpgradeTier>> tiers) {
        this.tiers.putAll(tiers);
    }

    public List<UpgradeTier> tiers(UpgradeType type) {
        return tiers.getOrDefault(type, List.of());
    }

    /** The tier to buy next given the team's current level, if any. */
    public Optional<UpgradeTier> nextTier(UpgradeType type, int currentLevel) {
        return tiers(type).stream().filter(tier -> tier.level() == currentLevel + 1).findFirst();
    }

    public static UpgradeCatalog defaults() {
        Map<UpgradeType, List<UpgradeTier>> map = new EnumMap<>(UpgradeType.class);
        map.put(UpgradeType.SHARPNESS, List.of(
                new UpgradeTier(UpgradeType.SHARPNESS, 1, Price.of(Currency.DIAMOND, 4), 1, "Sharpness I"),
                new UpgradeTier(UpgradeType.SHARPNESS, 2, Price.of(Currency.DIAMOND, 8), 2, "Sharpness II")));
        map.put(UpgradeType.PROTECTION, List.of(
                new UpgradeTier(UpgradeType.PROTECTION, 1, Price.of(Currency.DIAMOND, 2), 1, "Protection I"),
                new UpgradeTier(UpgradeType.PROTECTION, 2, Price.of(Currency.DIAMOND, 4), 2, "Protection II"),
                new UpgradeTier(UpgradeType.PROTECTION, 3, Price.of(Currency.DIAMOND, 8), 3, "Protection III"),
                new UpgradeTier(UpgradeType.PROTECTION, 4, Price.of(Currency.DIAMOND, 16), 4, "Protection IV")));
        map.put(UpgradeType.MANIAC_MINER, List.of(
                new UpgradeTier(UpgradeType.MANIAC_MINER, 1, Price.of(Currency.DIAMOND, 2), 1, "Haste I"),
                new UpgradeTier(UpgradeType.MANIAC_MINER, 2, Price.of(Currency.DIAMOND, 4), 2, "Haste II")));
        map.put(UpgradeType.IRON_FORGE, List.of(
                new UpgradeTier(UpgradeType.IRON_FORGE, 1, Price.of(Currency.DIAMOND, 4), 1.5, "Iron Forge I"),
                new UpgradeTier(UpgradeType.IRON_FORGE, 2, Price.of(Currency.DIAMOND, 8), 2.0, "Iron Forge II"),
                new UpgradeTier(UpgradeType.IRON_FORGE, 3, Price.of(Currency.DIAMOND, 12), 2.5, "Iron Forge III"),
                new UpgradeTier(UpgradeType.IRON_FORGE, 4, Price.of(Currency.DIAMOND, 16), 3.0, "Iron Forge IV")));
        map.put(UpgradeType.HEAL_POOL, List.of(
                new UpgradeTier(UpgradeType.HEAL_POOL, 1, Price.of(Currency.DIAMOND, 3), 1, "Heal Pool")));
        map.put(UpgradeType.DRAGON_BUFF, List.of(
                new UpgradeTier(UpgradeType.DRAGON_BUFF, 1, Price.of(Currency.DIAMOND, 5), 1, "Dragon Buff")));
        return new UpgradeCatalog(map);
    }
}