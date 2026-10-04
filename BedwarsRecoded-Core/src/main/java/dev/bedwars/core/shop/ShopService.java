package dev.bedwars.core.shop;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shop purchase rules. Currency is withdrawn here; the adapter is responsible for
 * giving the item in-world and for refunding {@link PurchaseResult#refunded()}.
 *
 * <p>Rules implemented:
 * <ul>
 *   <li>permanent items cannot be bought twice in a match;</li>
 *   <li>the wallet must hold the full price;</li>
 *   <li>buying a higher tier of a downgradable group reports the lower tier for refund.</li>
 * </ul>
 */
public final class ShopService {

    /**
     * @param shop          the shop the item belongs to (for refund lookup)
     * @param item          the item being purchased
     * @param wallet        the buyer's currency
     * @param ownedItemIds  ids of permanent items the buyer already owns this match
     */
    public PurchaseResult purchase(Shop shop, ShopItem item, CurrencyWallet wallet, Set<String> ownedItemIds) {
        if (shop.item(item.id()).isEmpty()) {
            return PurchaseResult.of(PurchaseStatus.UNKNOWN_ITEM);
        }
        if (item.permanent() && ownedItemIds.contains(item.id())) {
            return PurchaseResult.of(PurchaseStatus.ALREADY_OWNED);
        }
        if (wallet.balance(item.price().currency()) < item.price().amount()) {
            return PurchaseResult.of(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        if (!wallet.withdraw(item.price().currency(), item.price().amount())) {
            return PurchaseResult.of(PurchaseStatus.INSUFFICIENT_FUNDS);
        }
        Optional<ShopItem> refund = findRefund(shop, item, ownedItemIds);
        return refund.map(PurchaseResult::success).orElseGet(PurchaseResult::success);
    }

    /**
     * When buying tier N of a downgradable group, the highest owned lower tier is
     * refunded.
     */
    private Optional<ShopItem> findRefund(Shop shop, ShopItem item, Set<String> ownedItemIds) {
        if (item.upgradeGroup().isEmpty() || !item.downgradable()) {
            return Optional.empty();
        }
        String group = item.upgradeGroup().get();
        return shop.categories().stream()
                .flatMap(category -> category.items().stream())
                .filter(candidate -> candidate.upgradeGroup().map(group::equals).orElse(false))
                .filter(candidate -> candidate.tier() < item.tier())
                .filter(candidate -> ownedItemIds.contains(candidate.id()))
                .max(java.util.Comparator.comparingInt(ShopItem::tier));
    }

    /** Convenience for adapters: is this item affordable? */
    public boolean canAfford(ShopItem item, CurrencyWallet wallet) {
        return wallet.balance(item.price().currency()) >= item.price().amount();
    }

    public List<ShopItem> purchasable(ShopCategory category, CurrencyWallet wallet, Set<String> owned) {
        return category.items().stream()
                .filter(item -> !(item.permanent() && owned.contains(item.id())))
                .filter(item -> canAfford(item, wallet))
                .toList();
    }
}