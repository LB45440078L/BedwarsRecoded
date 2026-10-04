package dev.bedwars.core.upgrade;

import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.CurrencyWallet;
import dev.bedwars.core.shop.Price;
import dev.bedwars.core.shop.PurchaseStatus;

import java.util.Optional;

/**
 * Purchase rules for team upgrades and traps. Currency is withdrawn here; the
 * adapter applies the resulting effects (enchants, potion effects, forge tier).
 */
public final class UpgradeService {

    /** Buys the next level of {@code type} for the team, if affordable and not maxed. */
    public UpgradeResult purchaseUpgrade(UpgradeCatalog catalog, TeamUpgrades upgrades,
                                         UpgradeType type, CurrencyWallet wallet) {
        if (!upgrades.canUpgrade(type)) {
            return UpgradeResult.failed(PurchaseStatus.ALREADY_OWNED);
        }
        Optional<UpgradeTier> next = catalog.nextTier(type, upgrades.levelOf(type));
        if (next.isEmpty()) {
            return UpgradeResult.failed(PurchaseStatus.ALREADY_OWNED);
        }
        UpgradeTier tier = next.get();
        if (wallet.balance(tier.price().currency()) < tier.price().amount()) {
            return UpgradeResult.failed(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        if (!wallet.withdraw(tier.price().currency(), tier.price().amount())) {
            return UpgradeResult.failed(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        upgrades.setLevel(type, tier.level());
        return UpgradeResult.upgrade(tier);
    }

    /** Buys a trap for the team, if the trap queue has room and it is affordable. */
    public UpgradeResult purchaseTrap(TrapQueue traps, TrapType type, CurrencyWallet wallet) {
        if (traps.isFull()) {
            return UpgradeResult.failed(PurchaseStatus.ALREADY_OWNED);
        }
        int cost = type.costDiamonds(traps.size());
        if (wallet.balance(Currency.DIAMOND) < cost) {
            return UpgradeResult.failed(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        if (!wallet.withdraw(Currency.DIAMOND, cost)) {
            return UpgradeResult.failed(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        traps.add(type);
        return UpgradeResult.trap(type);
    }

    /** Current diamond cost of the next trap for a team. */
    public Price nextTrapCost(TrapType type, TrapQueue traps) {
        return Price.of(Currency.DIAMOND, type.costDiamonds(traps.size()));
    }
}