package dev.bedwars.core.scoreboard;

import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GameState;
import dev.bedwars.core.domain.PlayerSession;
import dev.bedwars.core.domain.Team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Produces the scoreboard's lines as plain (legacy-colour-coded) strings. Core
 * builds the content; the adapter renders it into a platform scoreboard. Keeping
 * it as strings means it is unit-testable without any server.
 */
public final class ScoreboardBuilder {

    public List<String> build(Game game, UUID player) {
        List<String> lines = new ArrayList<>();
        lines.add("&e&lBEDWARS");
        lines.add("&7" + game.group().id());
        lines.add("&r");

        game.session(player).ifPresent(session -> {
            lines.add("&fKills: &a" + session.kills());
            lines.add("&fFinal kills: &a" + session.finalKills());
            lines.add("&fBeds broken: &a" + session.bedsBroken());
            lines.add("&r");
            session.teamId().flatMap(game::team).ifPresent(team ->
                    lines.add("&fYour bed: " + (team.bed().isDestroyed() ? "&cDestroyed" : "&aSafe")));
        });

        lines.add("&r");
        lines.add("&fTeams:");
        for (Team team : game.teams()) {
            String marker = team.isEliminated() ? "&c✘" : (team.bed().isDestroyed() ? "&e✘bed" : "&a✔");
            lines.add("&" + team.color().legacyCode() + team.id() + " " + marker);
        }

        lines.add("&r");
        lines.add("&fNext: &b" + nextEvent(game));
        return lines;
    }

    private String nextEvent(Game game) {
        return switch (game.state()) {
            case WAITING -> "Waiting";
            case COUNTDOWN -> "Starting";
            case RUNNING -> game.group().suddenDeathAfterSecs() > 0 ? "Sudden death" : "In progress";
            case SUDDEN_DEATH -> "Sudden death";
            case ENDED, ABORTED -> "Game over";
        };
    }

    /** True if the scoreboard should currently be shown to the player. */
    public boolean shouldRender(Game game, PlayerSession session) {
        return game.state() != GameState.WAITING && session != null;
    }
}