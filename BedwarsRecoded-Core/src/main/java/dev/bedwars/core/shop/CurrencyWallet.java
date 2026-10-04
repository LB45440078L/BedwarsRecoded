package dev.bedwars.core.shop;

/**
 * A player's spendable resources for one match. Implemented by the adapter over
 * the player's real inventory; Core only needs counts and withdrawal.
 */
public interface CurrencyWallet {

    int balance(Currency currency);

    /**
     * Withdraws {@code amount} of {@code currency} if the balance is sufficient.
     *
     * @return true if the withdrawal happened
     */
    boolean withdraw(Currency currency, int amount);
}