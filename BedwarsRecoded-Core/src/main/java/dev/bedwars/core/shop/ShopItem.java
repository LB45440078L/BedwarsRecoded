package dev.bedwars.core.shop;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One purchasable shop entry.
 *
 * @param permanent     bought once per match, not consumed (armour, tools, sword)
 * @param downgradable  part of a tiered {@code upgradeGroup}; buying a higher tier
 *                      refunds the lower one (e.g. wooden -> stone -> iron sword)
 * @param upgradeGroup  shared group id for downgradable tiers, or empty
 * @param tier          position within {@code upgradeGroup} (0 = base)
 */
public record ShopItem(
        String id,
        String categoryId,
        String displayName,
        int slot,
        String materialName,
        int amount,
        Price price,
        List<String> enchantments,
        boolean permanent,
        boolean downgradable,
        Optional<String> upgradeGroup,
        int tier
) {
    public ShopItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(materialName, "materialName");
        Objects.requireNonNull(price, "price");
        enchantments = List.copyOf(enchantments);
        if (upgradeGroup == null) {
            upgradeGroup = Optional.empty();
        }
        if (tier < 0) {
            throw new IllegalArgumentException("tier must be >= 0");
        }
    }
}