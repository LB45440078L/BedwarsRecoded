package dev.bedwars.spigot.command;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Team;
import dev.bedwars.spigot.BedwarsRecodedPlugin;
import dev.bedwars.spigot.shop.QuickBuyMenu;
import dev.bedwars.spigot.shop.ShopMenu;
import dev.bedwars.spigot.upgrade.UpgradeMenu;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /bw status|start|stop|join|shop|upgrades|lang}. Uses legacy colour codes
 * so the plugin compiles and runs against the Spigot API.
 */
public final class BedwarsCommand implements CommandExecutor {

    private final BedwarsRecodedPlugin plugin;

    public BedwarsCommand(BedwarsRecodedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Game game = plugin.game();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> sender.sendMessage(ChatColor.YELLOW + "game=" + game.id() + " state=" + game.state()
                    + " players=" + game.playerCount() + " teams=" + game.teams().size());
            case "start" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                try {
                    game.startCountdown();
                    game.beginMatch(System.currentTimeMillis());
                    sender.sendMessage(ChatColor.GREEN + "Match started.");
                } catch (IllegalStateException e) {
                    sender.sendMessage(ChatColor.RED + "Cannot start: " + e.getMessage());
                }
            }
            case "stop" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                game.abort();
                plugin.gameManager().unregister(game.id());
                sender.sendMessage(ChatColor.YELLOW + "Match aborted.");
            }
            case "join" -> join(sender);
            case "shop" -> shop(sender, args);
            case "quickbuy" -> quickBuy(sender);
            case "upgrades" -> upgrades(sender);
            case "gui" -> openJoinGui(sender);
            case "lang" -> language(sender, args);
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadConfiguration();
                sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
            }
            default -> sender.sendMessage(ChatColor.RED
                    + "Usage: /bw status|start|stop|join|gui|shop|quickbuy|upgrades|lang|reload");
        }
        return true;
    }

    /**
     * Gate for administrative subcommands. Deliberately per-subcommand rather than on
     * the whole {@code /bw} command, so regular players can still join, shop and talk.
     * Console/RCON senders always pass.
     */
    private boolean requireAdmin(CommandSender sender) {
        if (!(sender instanceof Player) || sender.hasPermission("bedwars.admin")) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "No permission.");
        return false;
    }

    private void join(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        try {
            plugin.game().addPlayer(player.getUniqueId(), player.getName());
            plugin.gameManager().trackPlayer(player.getUniqueId(), plugin.game().id());
            player.sendMessage(ChatColor.GREEN + "Joined the match.");
        } catch (IllegalStateException e) {
            player.sendMessage(ChatColor.RED + "Cannot join: " + e.getMessage());
        }
    }

    private void shop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        String categoryId = args.length > 1 ? args[1] : plugin.shop().categories().getFirst().id();
        plugin.shop().category(categoryId).ifPresentOrElse(
                category -> ShopMenu.open(player, plugin.shop(), category, plugin.shopService(),
                        plugin.ownedItems(player.getUniqueId()), plugin.quickBuy()),
                () -> player.sendMessage(ChatColor.RED + "Unknown shop category: " + categoryId));
    }

    private void quickBuy(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        QuickBuyMenu.open(player, plugin.shop(), plugin.shopService(), plugin.quickBuy(),
                plugin.ownedItems(player.getUniqueId()));
    }

    private void openJoinGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        plugin.joinMenu().open(player);
    }

    private void upgrades(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        Team team = plugin.game().teamOf(player.getUniqueId())
                .flatMap(plugin.game()::team).orElse(null);
        if (team == null) {
            player.sendMessage(ChatColor.RED + "You are not on a team.");
            return;
        }
        UpgradeMenu.open(player, plugin.game(), team, plugin.upgradeCatalog(), plugin.upgradeService());
    }

    private void language(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "Available: "
                    + String.join(", ", plugin.languageService().catalog().codes()));
            return;
        }
        if (plugin.languageService().setLanguage(player.getUniqueId(), args[1])) {
            player.sendMessage(ChatColor.GREEN + "Language set to " + args[1] + ".");
        } else {
            player.sendMessage(ChatColor.RED + "Unknown language: " + args[1]);
        }
    }
}