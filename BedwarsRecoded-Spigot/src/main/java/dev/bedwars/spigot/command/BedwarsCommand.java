package dev.bedwars.spigot.command;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.manager.GameManager;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /bw status|start|stop}. Admin-facing; the pod hosts one match, so the
 * commands act on that match.
 */
public final class BedwarsCommand implements CommandExecutor {

    private final GameManager gameManager;
    private final Game game;

    public BedwarsCommand(GameManager gameManager, Game game) {
        this.gameManager = gameManager;
        this.game = game;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> sender.sendMessage(Component.text(
                    "game=" + game.id() + " state=" + game.state()
                            + " players=" + game.playerCount() + " teams=" + game.teams().size()));
            case "start" -> {
                try {
                    game.startCountdown();
                    game.beginMatch(System.currentTimeMillis());
                    sender.sendMessage(Component.text("Match started."));
                } catch (IllegalStateException e) {
                    sender.sendMessage(Component.text("Cannot start: " + e.getMessage()));
                }
            }
            case "stop" -> {
                game.abort();
                gameManager.unregister(game.id());
                sender.sendMessage(Component.text("Match aborted."));
            }
            default -> sender.sendMessage(Component.text("Usage: /bw status|start|stop"));
        }
        return true;
    }
}