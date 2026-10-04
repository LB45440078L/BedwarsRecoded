package dev.bedwars.spigot.listener;

import dev.bedwars.spigot.upgrade.UpgradeMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/** Routes clicks inside an {@link UpgradeMenu} to the upgrade service. */
public final class UpgradeListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof UpgradeMenu menu)) {
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