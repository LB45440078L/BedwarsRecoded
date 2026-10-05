package dev.bedwars.spigot.listener;

import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.event.GameEvent;
import dev.bedwars.api.service.PodHeartbeat;
import dev.bedwars.api.service.PodReporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression guard: the bridge must never throw while handling a domain event.
 * A throw here propagates into the Bukkit event (on a live server it showed up as
 * "Could not pass event PlayerDeathEvent") and aborts the rest of the listener chain.
 *
 * <p>The original defect was {@code Map.of(...)} used for structured-log fields:
 * {@code Map.of} rejects nulls, and a death with no killer / a drawn game
 * legitimately has none.
 */
class DomainEventBridgeTest {

    private static final class SilentReporter implements PodReporter {
        @Override
        public void reportReady(String podId, String arenaGroup, TemplateDescriptor template, String gameId) {
        }

        @Override
        public void reportGameStarted(String gameId, int playerCount) {
        }

        @Override
        public void reportGameEnded(String podId, GameResult result) {
        }

        @Override
        public void heartbeat(PodHeartbeat heartbeat) {
        }

        @Override
        public void reportDraining(String podId, String gameId, int remainingPlayers) {
        }
    }

    private static DomainEventBridge bridge() {
        return new DomainEventBridge(LoggerFactory.getLogger("test"), new SilentReporter(), true);
    }

    @Test
    void deathWithoutAKillerDoesNotThrow() {
        // Void kill / /kill: killer is empty.
        var event = new GameEvent.PlayerEliminated("game-1", UUID.randomUUID(), Optional.empty(), false);

        assertThatCode(() -> bridge().onGameEvent(event)).doesNotThrowAnyException();
    }

    @Test
    void deathWithAKillerDoesNotThrow() {
        var event = new GameEvent.PlayerEliminated("game-1", UUID.randomUUID(),
                Optional.of(UUID.randomUUID()), true);

        assertThatCode(() -> bridge().onGameEvent(event)).doesNotThrowAnyException();
    }

    @Test
    void drawnGameDoesNotThrow() {
        var event = new GameEvent.GameEnded("game-1", Optional.empty(), 60_000L);

        assertThatCode(() -> bridge().onGameEvent(event)).doesNotThrowAnyException();
    }

    @Test
    void nullValuesAreOmittedFromTheFieldMap() {
        var fields = DomainEventBridge.fields("game_id", "g", "killer_uuid", null, "final", true);

        assertThat(fields).containsEntry("game_id", "g").containsEntry("final", true);
        assertThat(fields).doesNotContainKey("killer_uuid");
    }

    @Test
    void fieldMapKeepsNonNullValues() {
        var fields = DomainEventBridge.fields("a", 1, "b", "two");

        assertThat(fields).containsExactly(
                java.util.Map.entry("a", 1),
                java.util.Map.entry("b", "two"));
    }
}