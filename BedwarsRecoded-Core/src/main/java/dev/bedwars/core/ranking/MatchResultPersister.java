package dev.bedwars.core.ranking;

import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.PlayerStatDelta;
import dev.bedwars.api.dto.PlayerStats;
import dev.bedwars.api.service.StatsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Persists a finished match: applies every player's additive stat delta and
 * recalculates ELO. This is the piece that actually writes to MySQL — the domain
 * produces a {@link GameResult}, this turns it into database updates.
 *
 * <p>ELO is computed team-vs-team: the mean rating of the winning side against the
 * mean of the losing side, so a full team is rated against the opposition rather
 * than pairwise. Draws (no winner, or no losers) skip the ELO update.
 */
public final class MatchResultPersister {

    private final EloCalculator elo;

    public MatchResultPersister(EloCalculator elo) {
        this.elo = elo;
    }

    /** Applies stat deltas and ELO updates; completes when all writes are issued. */
    public CompletableFuture<Void> persist(GameResult result, StatsService stats) {
        List<PlayerStatDelta> deltas = result.playerDeltas();
        if (deltas.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<PlayerStats>> loads = deltas.stream()
                .map(delta -> stats.load(delta.uuid(), delta.username()))
                .toList();

        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenCompose(ignored -> {
            Map<UUID, Integer> ratings = loads.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toMap(PlayerStats::uuid, PlayerStats::elo, (a, b) -> a));

            List<PlayerStatDelta> winners = deltas.stream().filter(PlayerStatDelta::winner).toList();
            List<PlayerStatDelta> losers = deltas.stream().filter(delta -> !delta.winner()).toList();
            boolean rated = !winners.isEmpty() && !losers.isEmpty();
            double winnerMean = meanRating(ratings, winners);
            double loserMean = meanRating(ratings, losers);

            List<CompletableFuture<Void>> writes = new ArrayList<>();
            for (PlayerStatDelta delta : deltas) {
                writes.add(stats.apply(delta));
                if (!rated) {
                    continue;
                }
                int current = ratings.getOrDefault(delta.uuid(), EloCalculator.MIN_RATING);
                double teamMean = delta.winner() ? winnerMean : loserMean;
                double opponentMean = delta.winner() ? loserMean : winnerMean;
                double outcome = delta.winner() ? 1.0 : 0.0;
                int newRating = elo.apply(current, elo.delta(teamMean, opponentMean, outcome));
                writes.add(stats.updateElo(delta.uuid(), newRating));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        });
    }

    private static double meanRating(Map<UUID, Integer> ratings, List<PlayerStatDelta> side) {
        if (side.isEmpty()) {
            return 0.0;
        }
        return side.stream()
                .mapToInt(delta -> ratings.getOrDefault(delta.uuid(), EloCalculator.MIN_RATING))
                .average()
                .orElse(0.0);
    }
}