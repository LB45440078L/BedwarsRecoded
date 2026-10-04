package dev.bedwars.spigot.shop;

import dev.bedwars.core.shop.QuickBuyStore;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.shop.ShopItem;
import dev.bedwars.core.shop.ShopService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
 * The quick-buy bar: one click buys a favourite item. Layout is per-player and
 * (when persistence is wired) synced across pods/proxies via
 * {@code dev.bedwars.core.persistence.QuickBuyRepository}.
 */
public final class QuickBuyMenu implements InventoryHolder {

    private final Inventory inventory;
    private final Shop shop;
    private final ShopService service;
    private final QuickBuyStore quickBuy;
    private final Set<String> owned;
    private final Map<Integer, ShopItem> itemsBySlot = new HashMap<>();

    private QuickBuyMenu(Inventory inventory, Shop shop, ShopService service, QuickBuyStore quickBuy, Set<String> owned) {
        this.inventory = inventory;
        this.shop = shop;
        this.service = service;
        this.quickBuy = quickBuy;
        this.owned = owned;
    }

    public static void open(Player player, Shop shop, ShopService service, QuickBuyStore quickBuy, Set<String> owned) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Quick Buy");
        QuickBuyMenu menu = new QuickBuyMenu(inventory, shop, service, quickBuy, owned);
        menu.populate(player);
        player.openInventory(inventory);
    }

    private void populate(Player player) {
        List<String> ids = quickBuy.items(player.getUniqueId());
        int slot = 0;
        for (String id : ids) {
            ShopItem item = shop.item(id).orElse(null);
            if (item == null) {
                continue;
            }
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
                meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            inventory.setItem(slot, stack);
            itemsBySlot.put(slot, item);
            slot++;
        }
    }

    public void onClick(Player player, int slot) {
        ShopItem item = itemsBySlot.get(slot);
        if (item == null) {
            return;
        }
        ShopMenu.open(player, shop, shop.category(item.categoryId()).orElseThrow(), service, owned, quickBuy);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}