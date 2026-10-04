package dev.bedwars.spigot.shop;

import dev.bedwars.core.shop.CurrencyWallet;
import dev.bedwars.core.shop.PurchaseResult;
import dev.bedwars.core.shop.PurchaseStatus;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.shop.ShopCategory;
import dev.bedwars.core.shop.ShopItem;
import dev.bedwars.core.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Bukkit-rendered shop category. Implements {@link InventoryHolder} so the
 * click listener can recover the menu and the item under a slot.
 */
public final class ShopMenu implements InventoryHolder {

    private final Inventory inventory;
    private final Shop shop;
    private final ShopCategory category;
    private final ShopService service;
    private final Set<String> owned;
    private final Map<Integer, ShopItem> itemsBySlot = new HashMap<>();

    private ShopMenu(Inventory inventory, Shop shop, ShopCategory category, ShopService service, Set<String> owned) {
        this.inventory = inventory;
        this.shop = shop;
        this.category = category;
        this.service = service;
        this.owned = owned;
    }

    public static ShopMenu open(Player player, Shop shop, ShopCategory category, ShopService service, Set<String> owned) {
        Inventory inventory = Bukkit.createInventory(null, 54,
                ChatColor.DARK_GRAY + shop.displayName() + " - " + category.displayName());
        ShopMenu menu = new ShopMenu(inventory, shop, category, service, owned);
        menu.populate();
        player.openInventory(inventory);
        return menu;
    }

    private void populate() {
        for (ShopItem item : category.items()) {
            Material material = Material.matchMaterial(item.materialName());
            if (material == null) {
                continue;
            }
            ItemStack stack = new ItemStack(material, Math.max(1, item.amount()));
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.GREEN + item.displayName());
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GOLD + "Cost: " + item.price().amount() + " " + item.price().currency().name());
                if (item.permanent()) {
                    lore.add(ChatColor.GRAY + "Permanent");
                }
                if (owned.contains(item.id())) {
                    lore.add(ChatColor.RED + "Owned");
                }
                meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            inventory.setItem(item.slot(), stack);
            itemsBySlot.put(item.slot(), item);
        }
    }

    /** Handles a click; performs the purchase and gives the item on success. */
    public void onClick(Player player, int slot) {
        ShopItem item = itemsBySlot.get(slot);
        if (item == null) {
            return;
        }
        CurrencyWallet wallet = new CurrencyWalletAdapter(player);
        PurchaseResult result = service.purchase(shop, item, wallet, owned);
        if (result.status() != PurchaseStatus.SUCCESS) {
            player.sendMessage(ChatColor.RED + switch (result.status()) {
                case INSUFFICIENT_FUNDS -> "You cannot afford that.";
                case ALREADY_OWNED -> "You already own that.";
                default -> "You cannot buy that.";
            });
            return;
        }
        give(player, item);
        player.sendMessage(ChatColor.GREEN + "Purchased " + item.displayName() + ".");
    }

    private void give(Player player, ShopItem item) {
        Material material = Material.matchMaterial(item.materialName());
        if (material == null) {
            return;
        }
        ItemStack stack = new ItemStack(material, Math.max(1, item.amount()));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            for (String enchant : item.enchantments()) {
                Enchantment enchantment = Enchantment.getByName(enchant.toUpperCase());
                if (enchantment != null) {
                    meta.addEnchant(enchantment, 1, true);
                }
            }
            stack.setItemMeta(meta);
        }
        player.getInventory().addItem(stack);
        if (item.permanent()) {
            owned.add(item.id());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}