package dev.bedwars.spigot.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /bwadmin} — the network's operator view, from inside the game.
 *
 * <p>The point is to answer the questions an operator actually asks when something
 * looks wrong, without access to the host:
 *
 * <ul>
 *   <li>{@code /bwadmin status}  — is the fleet up, how much capacity is free, is anyone waiting</li>
 *   <li>{@code /bwadmin servers} — every game server, its arena group and its free match slots</li>
 *   <li>{@code /bwadmin queue}   — who is waiting, per arena group</li>
 *   <li>{@code /bwadmin infra}   — the controller's own description of how it provisions</li>
 * </ul>
 *
 * <p>"Three players queued, no free slots, zero registered servers" is a different bug
 * from "three players queued, two servers idle", and both used to look identical from
 * in-game: nothing happened. Every command here tells them apart.
 *
 * <p>All controller calls are asynchronous; the output is rendered back on the server
 * thread so no Bukkit API is touched from a network thread.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = ChatColor.DARK_AQUA + "[Bedwars] " + ChatColor.RESET;
    private static final List<String> SUBCOMMANDS = List.of("status", "servers", "queue", "infra", "help");

    private final Plugin plugin;
    private final ControllerQuery controller;

    public AdminCommand(Plugin plugin, ControllerQuery controller) {
        this.plugin = plugin;
        this.controller = controller;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> status(sender);
            case "servers" -> servers(sender);
            case "queue" -> queue(sender);
            case "infra" -> infra(sender);
            case "help" -> help(sender, label);
            default -> {
                sender.sendMessage(PREFIX + ChatColor.RED + "Unknown subcommand: " + sub);
                help(sender, label);
                return true;
            }
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(PREFIX + ChatColor.GRAY + "Network management (no Docker access needed):");
        sender.sendMessage(ChatColor.YELLOW + "  /" + label + " status" + ChatColor.GRAY
                + "  - fleet, capacity and queue at a glance");
        sender.sendMessage(ChatColor.YELLOW + "  /" + label + " servers" + ChatColor.GRAY
                + " - every game server, its group and its free match slots");
        sender.sendMessage(ChatColor.YELLOW + "  /" + label + " queue" + ChatColor.GRAY
                + "   - who is waiting, per arena group");
        sender.sendMessage(ChatColor.YELLOW + "  /" + label + " infra" + ChatColor.GRAY
                + "   - how the controller provisions servers");
    }

    private void status(CommandSender sender) {
        if (!controller.configured()) {
            sender.sendMessage(PREFIX + ChatColor.RED + "No controller URL is configured "
                    + "(controller.base-url / BEDWARS_CONTROLLER_URL).");
            return;
        }
        controller.infra().thenAccept(infra -> controller.queueDepth().thenAccept(depth ->
                controller.arenaStatus().thenAccept(arena -> render(sender, () -> {
                    sender.sendMessage(PREFIX + ChatColor.GRAY + "Network status"
                            + ChatColor.DARK_GRAY + "  (auth " + (controller.authenticated() ? "token" : "none") + ")");
                    if (infra.isEmpty()) {
                        sender.sendMessage(ChatColor.RED + "  The controller is not answering. "
                                + "Check that it is running and reachable from this server.");
                        return;
                    }
                    line(sender, "provisioner", str(infra, "provisioner") + ChatColor.DARK_GRAY + "  "
                            + str(infra, "description"));
                    line(sender, "servers", "provisioned " + num(infra, "servers")
                            + ChatColor.DARK_GRAY + " | " + ChatColor.RESET + "registered " + num(infra, "registeredServers")
                            + ChatColor.DARK_GRAY + " | " + ChatColor.RESET + "free slots " + num(infra, "freeSlots"));
                    line(sender, "limits", "min " + num(infra, "minServers") + ", max " + num(infra, "maxServers")
                            + ", " + num(infra, "gamesPerServer") + " matches per server"
                            + ChatColor.DARK_GRAY + " -> " + ChatColor.RESET + num(infra, "gameCapacity") + " slots total");
                    line(sender, "queue", queueSummary(depth));
                    if (!arena.isEmpty()) {
                        for (String group : arena.keySet()) {
                            JsonObject entry = arena.getAsJsonObject(group);
                            line(sender, "  " + group, "waiting " + num(entry, "queued")
                                    + ", servers " + num(entry, "servers")
                                    + ", free " + num(entry, "freeSlots"));
                        }
                    }
                    diagnose(sender, infra, depth);
                }))));
    }

    /**
     * The one line that turns numbers into an answer. The reported failure was "nothing
     * happens": a queue that is not moving has several causes, and they look identical.
     */
    private void diagnose(CommandSender sender, JsonObject infra, JsonObject depth) {
        int waiting = total(depth);
        int registered = num(infra, "registeredServers");
        int free = num(infra, "freeSlots");
        int provisioned = num(infra, "servers");
        int maximum = num(infra, "maxServers");
        if (waiting == 0) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Nobody is waiting.");
        } else if (free > 0) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + waiting + " waiting with " + free
                    + " free slot(s): dispatch should be immediate. If it is not, the queue is stuck.");
        } else if (provisioned < registered) {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + waiting + " waiting, no free slot, and fewer servers "
                    + "running than registered - a server is probably shutting down.");
        } else if (provisioned >= maximum) {
            sender.sendMessage(PREFIX + ChatColor.RED + waiting + " waiting, no free slot, and the fleet is at its "
                    + "maximum (" + maximum + "). Raise the maximum or shorten matches.");
        } else {
            sender.sendMessage(PREFIX + ChatColor.YELLOW + waiting + " waiting, no free slot, "
                    + provisioned + " server(s) up: the controller should be starting another one.");
        }
    }

    private void servers(CommandSender sender) {
        controller.servers().thenAccept(body -> render(sender, () -> {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Game servers");
            if (body.isEmpty() || !body.has("servers")) {
                sender.sendMessage(ChatColor.RED + "  The controller is not answering.");
                return;
            }
            var list = body.getAsJsonArray("servers");
            if (list.isEmpty()) {
                sender.sendMessage(ChatColor.YELLOW + "  None registered yet. A server appears here the moment "
                        + "it boots and reports in.");
            }
            for (JsonElement element : list) {
                JsonObject server = element.getAsJsonObject();
                int capacity = num(server, "capacity");
                int freeSlots = num(server, "freeSlots");
                boolean idle = server.has("idle") && server.get("idle").getAsBoolean();
                String busy = (idle ? ChatColor.GREEN + "idle" : ChatColor.YELLOW + "hosting matches");
                sender.sendMessage(ChatColor.YELLOW + "  " + str(server, "serverId") + ChatColor.GRAY
                        + "  group " + str(server, "group")
                        + "  " + freeSlots + "/" + capacity + " slots free  " + busy);
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "  provisioned " + num(body, "provisioned")
                    + ", registered " + num(body, "count")
                    + ", limits " + num(body, "minServers") + "-" + num(body, "maxServers")
                    + ", " + num(body, "gamesPerServer") + " matches/server");
        }));
    }

    private void queue(CommandSender sender) {
        controller.queueDepth().thenAccept(depth -> render(sender, () -> {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Matchmaking queue");
            if (depth.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "  Empty.");
                return;
            }
            for (String group : depth.keySet()) {
                sender.sendMessage(ChatColor.YELLOW + "  " + group + ChatColor.GRAY
                        + "  " + depth.get(group).getAsInt() + " waiting");
            }
            sender.sendMessage(ChatColor.DARK_GRAY + "  " + total(depth) + " player(s) in total");
        }));
    }

    private void infra(CommandSender sender) {
        controller.infra().thenAccept(infra -> render(sender, () -> {
            sender.sendMessage(PREFIX + ChatColor.GRAY + "Controller");
            if (infra.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "  Not answering at " + ChatColor.GRAY + "the configured URL.");
                return;
            }
            for (String key : infra.keySet()) {
                line(sender, key, infra.get(key).getAsString());
            }
        }));
    }

    /** Renders on the server thread: a controller reply arrives on a network thread. */
    private void render(CommandSender sender, Runnable body) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, body);
        }
    }

    private static void line(CommandSender sender, String key, String value) {
        sender.sendMessage(ChatColor.YELLOW + "  " + key + ChatColor.GRAY + "  " + value);
    }

    private static String queueSummary(JsonObject depth) {
        int total = total(depth);
        if (total == 0) {
            return ChatColor.GREEN + "empty";
        }
        StringBuilder sb = new StringBuilder(total + " waiting");
        for (String group : depth.keySet()) {
            sb.append(ChatColor.DARK_GRAY).append(" | ").append(ChatColor.GRAY)
                    .append(group).append(' ').append(depth.get(group).getAsInt());
        }
        return sb.toString();
    }

    private static int total(JsonObject counts) {
        int sum = 0;
        for (String key : counts.keySet()) {
            sum += counts.get(key).getAsInt();
        }
        return sum;
    }

    private static String str(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "?";
    }

    private static int num(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : 0;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
            return out;
        }
        return List.of();
    }
}
