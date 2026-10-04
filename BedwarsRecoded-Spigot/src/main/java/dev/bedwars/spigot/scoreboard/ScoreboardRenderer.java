package dev.bedwars.spigot.scoreboard;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.scoreboard.ScoreboardBuilder;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders the Core-built scoreboard lines onto a Bukkit sidebar. One scoreboard
 * per player, refreshed in place; lines are carried as team prefixes so colours
 * and dynamic text work within the sidebar.
 */
public final class ScoreboardRenderer {

    private static final ChatColor[] ENTRIES = ChatColor.values();

    private final ScoreboardBuilder builder = new ScoreboardBuilder();
    private final java.util.Map<UUID, Scoreboard> boards = new ConcurrentHashMap<>();

    public void update(Player player, Game game) {
        if (game.session(player.getUniqueId()).isEmpty()) {
            return;
        }
        List<String> lines = builder.build(game, player.getUniqueId());
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), key -> {
            Scoreboard created = Bukkit.getScoreboardManager().getNewScoreboard();
            created.registerNewObjective("bw", "dummy", ChatColor.translateAlternateColorCodes('&', lines.get(0)));
            return created;
        });
        Objective objective = board.getObjective("bw");
        if (objective == null) {
            return;
        }
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Clear previous lines.
        for (int i = 0; i < ENTRIES.length; i++) {
            String entry = ENTRIES[i].toString();
            Team existing = board.getTeam("line" + i);
            if (existing != null) {
                existing.unregister();
            }
            board.resetScores(entry);
        }

        int size = Math.min(lines.size(), ENTRIES.length);
        for (int i = 0; i < size; i++) {
            String entry = ENTRIES[i].toString();
            String text = ChatColor.translateAlternateColorCodes('&', lines.get(i));
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry);
            team.setPrefix(truncate(text));
            objective.getScore(entry).setScore(size - i);
        }
        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 64 ? text : text.substring(0, 64);
    }

    public void forget(UUID player) {
        boards.remove(player);
    }
}