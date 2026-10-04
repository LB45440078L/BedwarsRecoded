package dev.bedwars.core.shop;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShopServiceTest {

    private static final class FakeWallet implements CurrencyWallet {
        private final Map<Currency, Integer> balances = new EnumMap<>(Currency.class);

        FakeWallet(int iron, int gold, int diamond, int emerald) {
            balances.put(Currency.IRON, iron);
            balances.put(Currency.GOLD, gold);
            balances.put(Currency.DIAMOND, diamond);
            balances.put(Currency.EMERALD, emerald);
        }

        @Override
        public int balance(Currency currency) {
            return balances.getOrDefault(currency, 0);
        }

        @Override
        public boolean withdraw(Currency currency, int amount) {
            int current = balance(currency);
            if (current < amount) {
                return false;
            }
            balances.put(currency, current - amount);
            return true;
        }
    }

    private final ShopItem wool = new ShopItem("wool", "blocks", "Wool", 0, "WHITE_WOOL", 16,
            Price.of(Currency.IRON, 4), List.of(), false, false, Optional.empty(), 0);
    private final ShopItem sword1 = new ShopItem("sword1", "combat", "Wooden Sword", 0, "WOODEN_SWORD", 1,
            Price.of(Currency.GOLD, 10), List.of(), true, true, Optional.of("sword"), 0);
    private final ShopItem sword2 = new ShopItem("sword2", "combat", "Stone Sword", 1, "STONE_SWORD", 1,
            Price.of(Currency.GOLD, 10), List.of(), true, true, Optional.of("sword"), 1);
    private final Shop shop = new Shop("default", "Shop", List.of(
            new ShopCategory("blocks", "Blocks", 0, "WHITE_WOOL", List.of(wool)),
            new ShopCategory("combat", "Combat", 1, "IRON_SWORD", List.of(sword1, sword2))));

    private final ShopService service = new ShopService();

    @Test
    void buysWhenAffordable() {
        FakeWallet wallet = new FakeWallet(4, 0, 0, 0);
        PurchaseResult result = service.purchase(shop, wool, wallet, Set.of());
        assertThat(result.status()).isEqualTo(PurchaseStatus.SUCCESS);
        assertThat(wallet.balance(Currency.IRON)).isZero();
    }

    @Test
    void rejectsWhenUnaffordable() {
        FakeWallet wallet = new FakeWallet(3, 0, 0, 0);
        PurchaseResult result = service.purchase(shop, wool, wallet, Set.of());
        assertThat(result.status()).isEqualTo(PurchaseStatus.INSUFFICIENT_FUNDS);
        assertThat(wallet.balance(Currency.IRON)).isEqualTo(3);
    }

    @Test
    void permanentItemCannotBeBoughtTwice() {
        FakeWallet wallet = new FakeWallet(0, 20, 0, 0);
        PurchaseResult result = service.purchase(shop, sword1, wallet, Set.of("sword1"));
        assertThat(result.status()).isEqualTo(PurchaseStatus.ALREADY_OWNED);
    }

    @Test
    void downgradableUpgradeReportsLowerTierForRefund() {
        FakeWallet wallet = new FakeWallet(0, 10, 0, 0);
        PurchaseResult result = service.purchase(shop, sword2, wallet, Set.of("sword1"));
        assertThat(result.status()).isEqualTo(PurchaseStatus.SUCCESS);
        assertThat(result.refunded()).contains(sword1);
    }

    @Test
    void unknownItemIsRejected() {
        ShopItem ghost = new ShopItem("ghost", "blocks", "Ghost", 9, "STONE", 1,
                Price.of(Currency.IRON, 1), List.of(), false, false, Optional.empty(), 0);
        PurchaseResult result = service.purchase(shop, ghost, new FakeWallet(10, 10, 10, 10), Set.of());
        assertThat(result.status()).isEqualTo(PurchaseStatus.UNKNOWN_ITEM);
    }
}