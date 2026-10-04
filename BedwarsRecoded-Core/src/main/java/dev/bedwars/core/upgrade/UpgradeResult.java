package dev.bedwars.core.upgrade;

import dev.bedwars.core.shop.PurchaseStatus;

import java.util.Objects;

/** Result of buying an upgrade or trap. */
public record UpgradeResult(PurchaseStatus status, UpgradeTier tier, TrapType trap) {

    public UpgradeResult {
        Objects.requireNonNull(status, "status");
    }

    public static UpgradeResult upgrade(UpgradeTier tier) {
        return new UpgradeResult(PurchaseStatus.SUCCESS, tier, null);
    }

    public static UpgradeResult trap(TrapType trap) {
        return new UpgradeResult(PurchaseStatus.SUCCESS, null, trap);
    }

    public static UpgradeResult failed(PurchaseStatus status) {
        return new UpgradeResult(status, null, null);
    }

    public boolean successful() {
        return status == PurchaseStatus.SUCCESS;
    }
}