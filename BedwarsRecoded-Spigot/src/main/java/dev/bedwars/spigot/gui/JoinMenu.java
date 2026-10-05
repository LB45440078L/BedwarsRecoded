package dev.bedwars.spigot.gui;

import dev.bedwars.core.manager.GameHost;
import dev.bedwars.spigot.game.JoinService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** A one-button GUI to join a match on this server. */
public final class JoinMenu implements InventoryHolder, Listener {

    private static final String TITLE = ChatColor.DARK_GREEN + "Join Bedwars";
    private static final int BUTTON_SLOT = 13;

    private final JoinService joinService;
    private final GameHost host;
    private final Inventory inventory;

    public JoinMenu(JoinService joinService, GameHost host) {
        this.joinService = joinService;
        this.host = host;
        this.inventory = Bukkit.createInventory(this, 27, TITLE);
        inventory.setItem(BUTTON_SLOT, button());
    }

    /** Rebuilt on each open so the player/capacity line is current. */
    private ItemStack button() {
        int players = host.games().stream().mapToInt(g -> g.playerCount()).sum();
        int capacity = host.maxGames() * host.arena().group().playersPerTeam() * host.arena().group().teamCount();
        ItemStack item = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Click to play");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Players: " + players + "/" + capacity,
                    ChatColor.GRAY + "Open matches: " + host.joinableGames()));
            item.setItemMeta(meta);
        }
        return item;
    }

    public void open(Player player) {
        inventory.setItem(BUTTON_SLOT, button());
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof JoinMenu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getSlot() != BUTTON_SLOT || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        player.closeInventory();
        if (joinService.isPlaying(player)) {
            player.sendMessage(ChatColor.YELLOW + "You are already in a match.");
            return;
        }
        if (joinService.join(player).isEmpty()) {
            player.sendMessage(ChatColor.RED + "No free match on this server right now.");
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
