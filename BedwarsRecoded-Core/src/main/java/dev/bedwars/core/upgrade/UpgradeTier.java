package dev.bedwars.core.upgrade;

import dev.bedwars.core.shop.Price;

import java.util.Objects;

/**
 * One purchasable level of an upgrade.
 *
 * @param effect numeric meaning depends on the type (damage, protection points,
 *               generator multiplier, regeneration ticks ...)
 */
public record UpgradeTier(UpgradeType type, int level, Price price, double effect, String description) {

    public UpgradeTier {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(description, "description");
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1");
        }
    }
}