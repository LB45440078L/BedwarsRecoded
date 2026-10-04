package dev.bedwars.core.upgrade;

import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.CurrencyWallet;
import dev.bedwars.core.shop.PurchaseStatus;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradeServiceTest {

    private static final class Wallet implements CurrencyWallet {
        private final Map<Currency, Integer> balances = new EnumMap<>(Currency.class);

        Wallet(int diamonds) {
            balances.put(Currency.DIAMOND, diamonds);
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

    private final UpgradeCatalog catalog = UpgradeCatalog.defaults();
    private final UpgradeService service = new UpgradeService();

    @Test
    void upgradesStepThroughLevels() {
        TeamUpgrades upgrades = new TeamUpgrades();
        Wallet wallet = new Wallet(12);

        assertThat(service.purchaseUpgrade(catalog, upgrades, UpgradeType.SHARPNESS, wallet).successful()).isTrue();
        assertThat(upgrades.levelOf(UpgradeType.SHARPNESS)).isEqualTo(1);
        assertThat(wallet.balance(Currency.DIAMOND)).isEqualTo(8); // 12 - 4

        assertThat(service.purchaseUpgrade(catalog, upgrades, UpgradeType.SHARPNESS, wallet).successful()).isTrue();
        assertThat(upgrades.levelOf(UpgradeType.SHARPNESS)).isEqualTo(2);
        assertThat(wallet.balance(Currency.DIAMOND)).isZero();
    }

    @Test
    void cannotExceedMaxLevel() {
        TeamUpgrades upgrades = new TeamUpgrades();
        upgrades.setLevel(UpgradeType.SHARPNESS, 2);
        UpgradeResult result = service.purchaseUpgrade(catalog, upgrades, UpgradeType.SHARPNESS, new Wallet(100));
        assertThat(result.status()).isEqualTo(PurchaseStatus.ALREADY_OWNED);
    }

    @Test
    void trapCostScalesWithOwned() {
        TrapQueue traps = new TrapQueue();
        assertThat(TrapType.IT_IS_A_TRAP.costDiamonds(0)).isEqualTo(1);
        assertThat(TrapType.IT_IS_A_TRAP.costDiamonds(1)).isEqualTo(2);
        assertThat(TrapType.IT_IS_A_TRAP.costDiamonds(2)).isEqualTo(4);
        assertThat(service.nextTrapCost(TrapType.ALARM, traps).amount()).isEqualTo(1);
    }

    @Test
    void buysTrapsUntilFull() {
        TrapQueue traps = new TrapQueue();
        Wallet wallet = new Wallet(100);
        assertThat(service.purchaseTrap(traps, TrapType.ALARM, wallet).successful()).isTrue();
        assertThat(service.purchaseTrap(traps, TrapType.ALARM, wallet).successful()).isTrue();
        assertThat(service.purchaseTrap(traps, TrapType.ALARM, wallet).successful()).isTrue();
        assertThat(traps.isFull()).isTrue();
        assertThat(service.purchaseTrap(traps, TrapType.ALARM, wallet).status())
                .isEqualTo(PurchaseStatus.ALREADY_OWNED);
    }

    @Test
    void trapsTriggerInOrder() {
        TrapQueue traps = new TrapQueue();
        traps.add(TrapType.ALARM);
        traps.add(TrapType.MINER_FATIGUE);
        assertThat(traps.triggerNext()).contains(TrapType.ALARM);
        assertThat(traps.triggerNext()).contains(TrapType.MINER_FATIGUE);
        assertThat(traps.triggerNext()).isEmpty();
    }
}