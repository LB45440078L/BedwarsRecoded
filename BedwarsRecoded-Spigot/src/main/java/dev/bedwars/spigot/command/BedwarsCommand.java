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
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /bw ...}. Uses legacy colour codes so it compiles and runs against the plain
 * Spigot API.
 *
 * <p>Because a server can host several matches, {@code status} lists them all and
 * {@code start}/{@code stop} accept a match id or {@code all}.
 */
public final class BedwarsCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "status", "queue", "join", "leave", "start", "stop", "create", "gui", "shop", "quickbuy",
            "upgrades", "lang", "reload");

    /**
     * Subcommands that only exist on a match host. In the lobby {@code plugin.host()},
     * the shop and the language service are not wired at all, so reaching them would be a
     * NullPointerException — better to say what this server is.
     */
    private static final java.util.Set<String> GAME_ONLY = java.util.Set.of(
            "start", "stop", "create", "shop", "quickbuy", "upgrades", "gui", "leave", "lang");

    private final BedwarsRecodedPlugin plugin;

    public BedwarsCommand(BedwarsRecodedPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        if (plugin.isLobby() && GAME_ONLY.contains(sub)) {
            sender.sendMessage(ChatColor.RED + "This is the network lobby - matches run on game servers."
                    + " Use " + ChatColor.YELLOW + "/bw queue" + ChatColor.RED + " to join one.");
            return true;
        }
        switch (sub) {
            case "help" -> help(sender);
            case "status" -> status(sender);
            case "queue" -> queue(sender);
            case "start" -> start(sender, args);
            case "stop" -> stop(sender, args);
            case "create" -> create(sender, args);
            case "join" -> join(sender);
            case "leave" -> leave(sender);
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
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try /bw help.");
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "Bedwars commands");
        if (plugin.isLobby()) {
            sender.sendMessage(ChatColor.YELLOW + "/bw queue" + ChatColor.GRAY
                    + " - find a match and be sent to it");
            sender.sendMessage(ChatColor.GRAY + "Or right-click a BedWars sign / NPC in the lobby.");
            if (!(sender instanceof Player) || sender.hasPermission("bedwars.admin")) {
                sender.sendMessage(ChatColor.YELLOW + "/bw reload" + ChatColor.GRAY + " - reload config.yml");
            }
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "/bw status" + ChatColor.GRAY + " - list matches on this server");
        sender.sendMessage(ChatColor.YELLOW + "/bw join" + ChatColor.GRAY + " - join a match");
        sender.sendMessage(ChatColor.YELLOW + "/bw leave" + ChatColor.GRAY + " - leave your match");
        sender.sendMessage(ChatColor.YELLOW + "/bw gui|shop|quickbuy|upgrades|lang" + ChatColor.GRAY
                + " - player menus");
        if (!(sender instanceof Player) || sender.hasPermission("bedwars.admin")) {
            sender.sendMessage(ChatColor.YELLOW + "/bw start <id|all>" + ChatColor.GRAY + " - force-start a match");
            sender.sendMessage(ChatColor.YELLOW + "/bw stop <id|all>" + ChatColor.GRAY + " - abort a match");
            sender.sendMessage(ChatColor.YELLOW + "/bw create <n>" + ChatColor.GRAY
                    + " - pre-warm n matches ahead of demand");
            sender.sendMessage(ChatColor.YELLOW + "/bw reload" + ChatColor.GRAY + " - reload config.yml / arena.yml");
        }
    }

    private void status(CommandSender sender) {
        if (plugin.isLobby()) {
            String world = sender instanceof Player player ? player.getWorld().getName() : "(console)";
            sender.sendMessage(ChatColor.GOLD + "Network lobby" + ChatColor.GRAY
                    + " - no matches here; players are routed to game servers.");
            sender.sendMessage(ChatColor.GRAY + "  world=" + world
                    + "  role=" + ChatColor.YELLOW + "LOBBY");
            sender.sendMessage(ChatColor.YELLOW + "  /bw queue" + ChatColor.GRAY
                    + " - search for a match (or right-click a [bedwars] sign/NPC)");
            sender.sendMessage(ChatColor.YELLOW + "  /bw leave" + ChatColor.GRAY
                    + " - stop searching / leave your match");
            // Live network numbers, so a player can see the queue is real and moving
            // instead of staring at a blank chat after typing /bw queue.
            var query = plugin.controllerQuery();
            if (query != null && query.configured()) {
                query.arenaStatus().thenAccept(status -> plugin.getServer().getScheduler()
                        .runTask(plugin, () -> {
                            if (status.isEmpty()) {
                                sender.sendMessage(ChatColor.GRAY + "  network: the controller is not answering");
                                return;
                            }
                            for (String group : status.keySet()) {
                                var state = status.getAsJsonObject(group);
                                sender.sendMessage(ChatColor.GRAY + "  " + group + ": "
                                        + ChatColor.YELLOW + state.get("queued").getAsInt() + " queued"
                                        + ChatColor.GRAY + ", " + ChatColor.YELLOW + state.get("servers").getAsInt()
                                        + " server(s)" + ChatColor.GRAY + ", " + ChatColor.YELLOW
                                        + state.get("freeSlots").getAsInt() + " slot(s) free");
                            }
                        }));
            }
            return;
        }
        List<Game> games = plugin.host().games();
        if (games.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No matches running. Capacity: "
                    + plugin.host().maxGames() + " (free slots: " + plugin.host().freeSlots() + ")");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "Matches on this server (" + games.size() + "/"
                + plugin.host().maxGames() + ", free slots " + plugin.host().freeSlots() + "):");
        for (Game game : games) {
            sender.sendMessage(ChatColor.YELLOW + "  " + game.id() + ChatColor.GRAY + " state=" + game.state()
                    + " players=" + game.playerCount() + " teams=" + game.teams().size());
        }
    }

    private void start(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        String target = args.length > 1 ? args[1] : "all";
        int started = 0;
        for (Game game : select(target)) {
            try {
                game.startCountdown();
                game.beginMatch(System.currentTimeMillis());
                started++;
            } catch (IllegalStateException e) {
                sender.sendMessage(ChatColor.RED + "Cannot start " + game.id() + ": " + e.getMessage());
            }
        }
        sender.sendMessage(started == 0 ? ChatColor.RED + "No matching match to start."
                : ChatColor.GREEN + "Started " + started + " match(es).");
    }

    private void stop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        String target = args.length > 1 ? args[1] : "all";
        int stopped = 0;
        for (Game game : select(target)) {
            game.abort();
            stopped++;
        }
        sender.sendMessage(stopped == 0 ? ChatColor.RED + "No matching match to stop."
                : ChatColor.YELLOW + "Aborted " + stopped + " match(es).");
    }

    private void create(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return;
        }
        int count = 1;
        if (args.length > 1) {
            try {
                count = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Usage: /bw create <n>");
                return;
            }
        }
        int created = 0;
        while (created < count && plugin.host().gameCount() < plugin.host().maxGames()) {
            plugin.host().createGame(System.currentTimeMillis());
            created++;
        }
        sender.sendMessage(ChatColor.GREEN + "Created " + created + " match(es). Now "
                + plugin.host().gameCount() + "/" + plugin.host().maxGames() + " on this server.");
    }

    private List<Game> select(String target) {
        if ("all".equalsIgnoreCase(target)) {
            return plugin.host().games();
        }
        return plugin.host().byId(target).map(List::of).orElse(List.of());
    }

    private void queue(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (plugin.isLobby()) {
            plugin.lobbyQueue().queue(player);
            return;
        }
        // On a match host the player is already on the server that would host the game,
        // so "queue" is simply "join the local match".
        join(sender);
    }

    private void join(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (plugin.isLobby()) {
            plugin.lobbyQueue().queue(player);
            return;
        }
        if (plugin.joinService().isPlaying(player)) {
            player.sendMessage(ChatColor.YELLOW + "You are already in a match.");
            return;
        }
        if (plugin.joinService().join(player).isEmpty()) {
            player.sendMessage(ChatColor.RED + "No free match on this server right now.");
        }
    }

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Players only.");
            return;
        }
        if (plugin.lobbyQueue().isSearching(player.getUniqueId())) {
            // Waiting in the lobby: cancelling has to reach the proxy too, or it keeps
            // searching and moves the player anyway.
            plugin.lobbyQueue().cancel(player);
            player.sendMessage(ChatColor.YELLOW + "You left the queue.");
            return;
        }
        if (!plugin.joinService().isPlaying(player)) {
            player.sendMessage(ChatColor.YELLOW + "You are not in a match.");
            return;
        }
        plugin.joinService().leave(player);
        player.sendMessage(ChatColor.GREEN + "You left the match.");
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
        Game game = plugin.gameManager().byPlayer(player.getUniqueId()).orElse(null);
        if (game == null) {
            player.sendMessage(ChatColor.RED + "You are not in a match.");
            return;
        }
        Team team = game.teamOf(player.getUniqueId()).flatMap(game::team).orElse(null);
        if (team == null) {
            player.sendMessage(ChatColor.RED + "You are not on a team.");
            return;
        }
        UpgradeMenu.open(player, game, team, plugin.upgradeCatalog(), plugin.upgradeService());
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

    private boolean requireAdmin(CommandSender sender) {
        if (!(sender instanceof Player) || sender.hasPermission("bedwars.admin")) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "No permission.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop"))) {
            List<String> options = new ArrayList<>();
            options.add("all");
            plugin.host().games().forEach(game -> options.add(game.id()));
            return options.stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            return List.of("1", "2", "3", "5");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("shop")) {
            return plugin.shop().categories().stream().map(c -> c.id())
                    .filter(id -> id.startsWith(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
