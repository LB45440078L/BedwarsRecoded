package dev.bedwars.api.dto;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured, analytics-ready payload emitted when a match ends. Serialised to
 * JSON and POSTed to the controller webhook. Deliberately contains everything a
 * downstream warehouse needs without a second round trip.
 */
public record GameResult(
        String gameId,
        String arenaGroup,
        TemplateDescriptor template,
        long startedAtMillis,
        long endedAtMillis,
        Optional<String> winnerTeamId,
        List<PlayerStatDelta> playerDeltas,
        List<BedBreak> bedBreaks
) {
    public GameResult {
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(arenaGroup, "arenaGroup");
        Objects.requireNonNull(template, "template");
        if (winnerTeamId == null) {
            winnerTeamId = Optional.empty();
        }
        playerDeltas = List.copyOf(playerDeltas);
        bedBreaks = List.copyOf(bedBreaks);
    }

    public long durationMillis() {
        return endedAtMillis - startedAtMillis;
    }
}