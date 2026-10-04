package dev.bedwars.spigot.listener;

import dev.bedwars.spigot.shop.QuickBuyMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Routes clicks inside a {@link QuickBuyMenu} to the shop service. */
public final class QuickBuyListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof QuickBuyMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null
                || !event.getClickedInventory().equals(event.getInventory())
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        menu.onClick(player, event.getSlot());
    }
}