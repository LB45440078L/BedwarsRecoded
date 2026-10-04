package dev.bedwars.spigot.upgrade;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.shop.CurrencyWallet;
import dev.bedwars.core.upgrade.TrapType;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import dev.bedwars.core.upgrade.UpgradeResult;
import dev.bedwars.core.upgrade.UpgradeService;
import dev.bedwars.core.upgrade.UpgradeType;
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

/**
 * The upgrade merchant menu: one icon per upgrade track plus the trap purchases.
 * Clicking buys the next tier for the player's team.
 */
public final class UpgradeMenu implements InventoryHolder {

    private static final Map<UpgradeType, Material> UPGRADE_ICONS = Map.of(
            UpgradeType.SHARPNESS, Material.IRON_SWORD,
            UpgradeType.PROTECTION, Material.DIAMOND_CHESTPLATE,
            UpgradeType.MANIAC_MINER, Material.GOLDEN_PICKAXE,
            UpgradeType.IRON_FORGE, Material.FURNACE,
            UpgradeType.HEAL_POOL, Material.BEACON,
            UpgradeType.DRAGON_BUFF, Material.DRAGON_EGG);

    private final Inventory inventory;
    private final Game game;
    private final Team team;
    private final UpgradeCatalog catalog;
    private final UpgradeService service;
    private final Map<Integer, UpgradeType> upgradeSlots = new HashMap<>();
    private final Map<Integer, TrapType> trapSlots = new HashMap<>();

    private UpgradeMenu(Inventory inventory, Game game, Team team, UpgradeCatalog catalog, UpgradeService service) {
        this.inventory = inventory;
        this.game = game;
        this.team = team;
        this.catalog = catalog;
        this.service = service;
    }

    public static void open(Player player, Game game, Team team, UpgradeCatalog catalog, UpgradeService service) {
        Inventory inventory = Bukkit.createInventory(null, 27, ChatColor.DARK_GRAY + "Team Upgrades");
        UpgradeMenu menu = new UpgradeMenu(inventory, game, team, catalog, service);
        menu.populate();
        player.openInventory(inventory);
    }

    private void populate() {
        int slot = 0;
        for (UpgradeType type : UpgradeType.values()) {
            ItemStack stack = new ItemStack(UPGRADE_ICONS.getOrDefault(type, Material.PAPER));
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.AQUA + type.displayName()
                        + " (" + team.upgrades().levelOf(type) + "/" + type.maxLevel() + ")");
                List<String> lore = new ArrayList<>();
                catalog.nextTier(type, team.upgrades().levelOf(type))
                        .ifPresent(tier -> lore.add(ChatColor.GOLD + "Cost: " + tier.price().amount()
                                + " " + tier.price().currency().name()));
                if (!team.upgrades().canUpgrade(type)) {
                    lore.add(ChatColor.RED + "Maxed");
                }
                meta.setLore(lore);
                stack.setItemMeta(meta);
            }
            inventory.setItem(slot, stack);
            upgradeSlots.put(slot, type);
            slot++;
        }
        int trapSlot = 9;
        for (TrapType trap : TrapType.values()) {
            ItemStack stack = new ItemStack(Material.REDSTONE_TORCH);
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.RED + trap.displayName());
                meta.setLore(List.of(ChatColor.GOLD + "Cost: " + trap.costDiamonds(team.traps().size()) + " DIAMOND",
                        ChatColor.GRAY + "Armed traps: " + team.traps().size() + "/" + 3));
                stack.setItemMeta(meta);
            }
            inventory.setItem(trapSlot, stack);
            trapSlots.put(trapSlot, trap);
            trapSlot++;
        }
    }

    public void onClick(Player player, int slot) {
        CurrencyWallet wallet = new dev.bedwars.spigot.shop.CurrencyWalletAdapter(player);
        UpgradeType upgrade = upgradeSlots.get(slot);
        if (upgrade != null) {
            UpgradeResult result = service.purchaseUpgrade(catalog, team.upgrades(), upgrade, wallet);
            if (result.successful() && upgrade == UpgradeType.IRON_FORGE) {
                game.applyForge(team.id(), System.currentTimeMillis());
            }
            player.sendMessage(result.successful()
                    ? ChatColor.GREEN + "Upgraded " + upgrade.displayName() + " to level " + team.upgrades().levelOf(upgrade)
                    : ChatColor.RED + "Cannot buy that upgrade (" + result.status() + ").");
            populate();
            return;
        }
        TrapType trap = trapSlots.get(slot);
        if (trap != null) {
            UpgradeResult result = service.purchaseTrap(team.traps(), trap, wallet);
            player.sendMessage(result.successful()
                    ? ChatColor.GREEN + "Armed trap: " + trap.displayName()
                    : ChatColor.RED + "Cannot buy that trap (" + result.status() + ").");
            populate();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}