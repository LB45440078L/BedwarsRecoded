package dev.bedwars.core.ranking;

import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.PlayerStatDelta;
import dev.bedwars.api.dto.PlayerStats;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.api.service.StatsService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** The persistence path that actually writes match stats and ELO to MySQL. */
class MatchResultPersisterTest {

    private static final UUID WINNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LOSER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final class FakeStats implements StatsService {
        final Map<UUID, Integer> elo = new HashMap<>();
        final Map<UUID, String> names = new HashMap<>();
        final List<PlayerStatDelta> applied = new ArrayList<>();

        @Override
        public CompletableFuture<PlayerStats> load(UUID uuid, String username) {
            names.put(uuid, username);
            return CompletableFuture.completedFuture(new PlayerStats(
                    uuid, username, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, elo.getOrDefault(uuid, 1000)));
        }

        @Override
        public CompletableFuture<Void> apply(PlayerStatDelta delta) {
            applied.add(delta);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> updateElo(UUID uuid, int newElo) {
            elo.put(uuid, newElo);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static GameResult result(List<PlayerStatDelta> deltas, Optional<String> winner) {
        return new GameResult("g-1", "solo",
                new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.S3, Optional.empty()),
                0L, 60_000L, winner, deltas, List.of());
    }

    @Test
    void appliesEveryDeltaAndMovesWinnerUpLoserDown() {
        FakeStats stats = new FakeStats();
        stats.elo.put(WINNER, 1000);
        stats.elo.put(LOSER, 1000);

        PlayerStatDelta winner = new PlayerStatDelta(WINNER, "Winner", 5, 2, 1, 0, 1, 0, true, 100);
        PlayerStatDelta loser = new PlayerStatDelta(LOSER, "Loser", 1, 0, 3, 1, 0, 1, false, 20);

        new MatchResultPersister(new EloCalculator(32))
                .persist(result(List.of(winner, loser), Optional.of("blue")), stats)
                .join();

        assertThat(stats.applied).containsExactlyInAnyOrder(winner, loser);
        assertThat(stats.names).containsEntry(WINNER, "Winner").containsEntry(LOSER, "Loser");
        // Both rated 1000: expected 0.5, so K*(1-0.5)=+16 and K*(0-0.5)=-16.
        assertThat(stats.elo.get(WINNER)).isEqualTo(1016);
        assertThat(stats.elo.get(LOSER)).isEqualTo(984);
    }

    @Test
    void statsAreAppliedButEloIsUntouchedForADraw() {
        FakeStats stats = new FakeStats();
        stats.elo.put(WINNER, 1200);
        stats.elo.put(LOSER, 1200);

        PlayerStatDelta a = new PlayerStatDelta(WINNER, "A", 1, 0, 1, 0, 0, 0, false, 5);
        PlayerStatDelta b = new PlayerStatDelta(LOSER, "B", 1, 0, 1, 0, 0, 0, false, 5);

        new MatchResultPersister(new EloCalculator(32))
                .persist(result(List.of(a, b), Optional.empty()), stats)
                .join();

        assertThat(stats.applied).hasSize(2);
        assertThat(stats.elo.get(WINNER)).isEqualTo(1200);
        assertThat(stats.elo.get(LOSER)).isEqualTo(1200);
    }

    @Test
    void emptyResultCompletesWithoutAnyWrite() {
        FakeStats stats = new FakeStats();
        new MatchResultPersister(new EloCalculator(32))
                .persist(result(List.of(), Optional.empty()), stats)
                .join();
        assertThat(stats.applied).isEmpty();
    }
}