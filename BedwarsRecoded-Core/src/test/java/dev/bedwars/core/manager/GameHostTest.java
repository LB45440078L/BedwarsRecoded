package dev.bedwars.core.manager;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.core.config.ArenaDefinition;
import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.event.EventBus;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many matches on one server: players fill a match before a new one is created, the
 * server never exceeds {@code games-per-server}, and capacity is reported as free
 * <em>match slots</em> rather than whole servers.
 */
class GameHostTest {

    private static final long NOW = 1_000L;

    private EventBus bus;
    private ArenaDefinition arena;
    private TemplateDescriptor template;
    private GameManager manager;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        template = new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL, Optional.empty());
        manager = new GameManager();
        ArenaGroup group = new ArenaGroup("solo", 2, 2, 15, 300, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON, GeneratorType.GOLD));
        arena = new ArenaDefinition(group,
                Map.of("red", new Vec3(0, 64, 0), "blue", new Vec3(100, 64, 0)),
                Map.of("red", new Vec3(0, 64, 0), "blue", new Vec3(100, 64, 0)),
                List.of(), new Shop("default", "Item Shop", List.of()), List.of(), UpgradeCatalog.defaults());
    }

    private GameHost host(int maxGames) {
        return new GameHost(arena, template, bus, manager, maxGames, 5, "game");
    }

    @Test
    void playersFillOneMatchBeforeAnotherIsCreated() {
        GameHost host = host(4);
        // 2 teams x 2 = 4 slots in the first match.
        for (int i = 0; i < 4; i++) {
            assertThat(host.join(UUID.randomUUID(), "p" + i, NOW)).isPresent();
        }
        assertThat(host.gameCount()).isEqualTo(1);

        // The fifth player cannot fit and starts a second match.
        assertThat(host.join(UUID.randomUUID(), "p5", NOW)).isPresent();
        assertThat(host.gameCount()).isEqualTo(2);
    }

    @Test
    void neverExceedsTheConfiguredGamesPerServer() {
        GameHost host = host(1);
        for (int i = 0; i < 4; i++) {
            assertThat(host.join(UUID.randomUUID(), "p" + i, NOW)).isPresent();
        }
        // Server is full and may not create a second match.
        assertThat(host.join(UUID.randomUUID(), "overflow", NOW)).isEmpty();
        assertThat(host.gameCount()).isEqualTo(1);
    }

    @Test
    void aTrackedPlayerRejoinsTheSameMatch() {
        GameHost host = host(4);
        UUID uuid = UUID.randomUUID();
        Game first = host.join(uuid, "alice", NOW).orElseThrow();
        Game second = host.join(uuid, "alice", NOW).orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(host.gameCount()).isEqualTo(1);
    }

    @Test
    void freeSlotsCountStartedMatchesNotWholeServers() {
        GameHost host = host(3);
        assertThat(host.freeSlots()).isEqualTo(3);

        // Populate a match (one player per team) so it can actually run.
        Game game = host.join(UUID.randomUUID(), "alice", NOW).orElseThrow();
        host.join(UUID.randomUUID(), "bob", NOW);
        assertThat(host.gameCount()).isEqualTo(1);
        game.startCountdown();
        game.beginMatch(NOW);
        assertThat(game.state()).isEqualTo(dev.bedwars.core.domain.GameState.RUNNING);

        assertThat(host.inProgressGames()).isEqualTo(1);
        assertThat(host.freeSlots()).isEqualTo(2);
    }

    @Test
    void createdButUnstartedMatchesStillConsumeSlots() {
        GameHost host = host(2);
        host.createGame(NOW);
        host.createGame(NOW);
        // Both slots are held by waiting matches, so no *new* match can be created...
        assertThat(host.freeSlots()).isZero();
        // ...but a joiner is still placed into an existing waiting match.
        assertThat(host.join(UUID.randomUUID(), "late", NOW)).isPresent();
        assertThat(host.gameCount()).isEqualTo(2);
    }

    @Test
    void finishedMatchesArePrunedAndUnregistered() {
        GameHost host = host(4);
        UUID uuid = UUID.randomUUID();
        Game game = host.join(uuid, "alice", NOW).orElseThrow();
        game.startCountdown();
        game.beginMatch(NOW);
        // Single populated team -> the match ends immediately.
        assertThat(game.state().isTerminal()).isTrue();
        assertThat(manager.byPlayer(uuid)).isPresent();

        host.pruneFinished();

        assertThat(host.gameCount()).isZero();
        assertThat(manager.byPlayer(uuid)).isEmpty();
    }

    @Test
    void matchesAreIndependent() {
        GameHost host = host(4);
        Game a = host.createGame(NOW);
        Game b = host.createGame(NOW);
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(host.arena()).isSameAs(arena);
        assertThat(a.teams()).hasSize(2);
        assertThat(b.teams()).hasSize(2);
    }
}
