package dev.bedwars.core.domain;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.api.event.GameEvent;
import dev.bedwars.core.event.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameLifecycleTest {

    private static final long NOW = 1_000L;

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();
    private final UUID p4 = UUID.randomUUID();

    private final List<GameEvent> events = new ArrayList<>();
    private Game game;

    @BeforeEach
    void setUp() {
        EventBus bus = new EventBus();
        bus.subscribe(events::add);

        ArenaGroup group = new ArenaGroup("solo", 2, 2, 15, 300, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON, GeneratorType.GOLD));
        TemplateDescriptor template = new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL, Optional.empty());

        Team red = new Team("red", TeamColor.RED, new Bed("red", new Vec3(0, 64, 0), 3.0), 2);
        Team blue = new Team("blue", TeamColor.BLUE, new Bed("blue", new Vec3(100, 64, 0), 3.0), 2);

        game = new Game("g1", group, template, List.of(red, blue), List.of(), bus, 5, NOW);
    }

    @Test
    void playersAreAutoBalancedAcrossTeams() {
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.addPlayer(p3, "carol");

        assertThat(game.session(p1).orElseThrow().teamId()).contains("red");
        assertThat(game.session(p2).orElseThrow().teamId()).contains("blue");
        // third player goes to whichever team is smaller (red again)
        assertThat(game.session(p3).orElseThrow().teamId()).contains("red");
        assertThat(game.playerCount()).isEqualTo(3);
    }

    @Test
    void joinRejectedAfterMatchStarted() {
        game.addPlayer(p1, "alice");
        game.startCountdown();
        game.beginMatch(NOW);
        assertThatThrownBy(() -> game.addPlayer(p2, "bob"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nonFinalDeathRespawnsWhenBedStands() {
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.startCountdown();
        game.beginMatch(NOW);

        game.recordDamage(p1, p2, NOW);
        game.onDeath(p1, NOW);

        PlayerSession victim = game.session(p1).orElseThrow();
        assertThat(victim.state()).isEqualTo(PlayerState.RESPAWNING);
        assertThat(victim.deaths()).isEqualTo(1);
        assertThat(victim.finalDeaths()).isZero();
        assertThat(game.session(p2).orElseThrow().kills()).isEqualTo(1);
        assertThat(game.session(p2).orElseThrow().finalKills()).isZero();
    }

    @Test
    void bedDestructionThenEliminationEndsGameWithSurvivor() {
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.addPlayer(p3, "carol");
        game.addPlayer(p4, "dave");
        game.startCountdown();
        game.beginMatch(NOW);

        assertThat(game.destroyBed("red", p2, NOW)).isTrue();
        assertThat(game.destroyBed("red", p2, NOW)).isFalse(); // idempotent
        assertThat(game.session(p2).orElseThrow().bedsBroken()).isEqualTo(1);
        assertThat(game.session(p1).orElseThrow().bedsLost()).isEqualTo(1);

        // p2 (blue) eliminates p1 (red); p4 (blue) eliminates p3 (red).
        // Red has no bed and no active members -> eliminated -> blue wins.
        game.recordDamage(p1, p2, NOW);
        game.onDeath(p1, NOW);
        assertThat(game.session(p1).orElseThrow().state()).isEqualTo(PlayerState.ELIMINATED);
        assertThat(game.session(p2).orElseThrow().finalKills()).isEqualTo(1);

        game.recordDamage(p3, p4, NOW);
        game.onDeath(p3, NOW);

        assertThat(game.state()).isEqualTo(GameState.ENDED);
        assertThat(game.winnerTeamId()).contains("blue");
    }

    @Test
    void suddenDeathDestroysAllBedsAndMaxesGenerators() {
        Generator gen = new Generator("iron-1", GeneratorType.IRON, GeneratorTier.I, new Vec3(0, 64, 0), NOW);
        EventBus bus = new EventBus();
        ArenaGroup group = new ArenaGroup("solo", 2, 2, 15, 300, 0.0, 30.0, 3.0, List.of(GeneratorType.IRON));
        Team red = new Team("red", TeamColor.RED, new Bed("red", new Vec3(0, 64, 0), 3.0), 2);
        Team blue = new Team("blue", TeamColor.BLUE, new Bed("blue", new Vec3(100, 64, 0), 3.0), 2);
        Game g = new Game("g2", group, new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL, Optional.empty()),
                List.of(red, blue), List.of(gen), bus, 5, NOW);
        g.addPlayer(p1, "alice");
        g.addPlayer(p2, "bob");
        g.startCountdown();
        g.beginMatch(NOW);

        g.enterSuddenDeath(NOW + 10_000);

        assertThat(g.state()).isEqualTo(GameState.SUDDEN_DEATH);
        assertThat(red.bed().isDestroyed()).isTrue();
        assertThat(blue.bed().isDestroyed()).isTrue();
        assertThat(gen.tier()).isEqualTo(GeneratorTier.MAX);
    }

    @Test
    void illegalTransitionThrows() {
        assertThatThrownBy(() -> game.beginMatch(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resultsContainWinnerFlagAndDeltas() {
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.startCountdown();
        game.beginMatch(NOW);
        game.destroyBed("red", p2, NOW);
        game.recordDamage(p1, p2, NOW);
        game.onDeath(p1, NOW);

        var result = game.results();
        assertThat(result.gameId()).isEqualTo("g1");
        assertThat(result.winnerTeamId()).contains("blue");
        assertThat(result.playerDeltas()).hasSize(2);
        assertThat(result.playerDeltas())
                .filteredOn(d -> d.uuid().equals(p2))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.winner()).isTrue();
                    assertThat(d.bedsBroken()).isEqualTo(1);
                });
    }
}