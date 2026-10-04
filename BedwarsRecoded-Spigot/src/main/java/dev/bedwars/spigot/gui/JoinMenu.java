package dev.bedwars.spigot.gui;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.manager.GameManager;
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

/** A one-button GUI to join the pod's match. */
public final class JoinMenu implements InventoryHolder, Listener {

    private static final String TITLE = ChatColor.DARK_GREEN + "Join Bedwars";

    private final GameManager gameManager;
    private final Game game;
    private final Inventory inventory;

    public JoinMenu(GameManager gameManager, Game game) {
        this.gameManager = gameManager;
        this.game = game;
        this.inventory = Bukkit.createInventory(this, 27, TITLE);
        ItemStack button = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GREEN + "Click to join");
            meta.setLore(List.of(ChatColor.GRAY + "Players: " + game.playerCount()
                    + "/" + game.group().capacity()));
            button.setItemMeta(meta);
        }
        inventory.setItem(13, button);
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof JoinMenu)) {
            return;
        }
        event.setCancelled(true);
        if (event.getSlot() != 13 || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        player.closeInventory();
        if (game.session(player.getUniqueId()).isPresent()) {
            player.sendMessage(ChatColor.YELLOW + "You are already in the match.");
            return;
        }
        try {
            game.addPlayer(player.getUniqueId(), player.getName());
            gameManager.trackPlayer(player.getUniqueId(), game.id());
            player.sendMessage(ChatColor.GREEN + "Joined the match.");
        } catch (IllegalStateException e) {
            player.sendMessage(ChatColor.RED + "Cannot join: " + e.getMessage());
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}