package dev.bedwars.spigot.shop;

import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.CurrencyWallet;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@link CurrencyWallet} backed by a player's real inventory. Counts and removes
 * ingots/gems by material.
 */
public final class CurrencyWalletAdapter implements CurrencyWallet {

    private final Player player;

    public CurrencyWalletAdapter(Player player) {
        this.player = player;
    }

    @Override
    public int balance(Currency currency) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material(currency)) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    @Override
    public boolean withdraw(Currency currency, int amount) {
        if (balance(currency) < amount) {
            return false;
        }
        Material material = material(currency);
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            remaining -= take;
            if (stack.getAmount() <= 0) {
                player.getInventory().setItem(slot, null);
            }
        }
        return true;
    }

    public static Material material(Currency currency) {
        return switch (currency) {
            case IRON -> Material.IRON_INGOT;
            case GOLD -> Material.GOLD_INGOT;
            case DIAMOND -> Material.DIAMOND;
            case EMERALD -> Material.EMERALD;
        };
    }
}