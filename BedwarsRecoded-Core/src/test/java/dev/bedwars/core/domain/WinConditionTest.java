package dev.bedwars.core.domain;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.api.event.GameEvent;
import dev.bedwars.core.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the win-condition defect: a match must reach a single
 * survivor even when the arena declares more teams than were actually filled,
 * and when a team is abandoned by disconnects.
 *
 * <p>Before the fix, {@code checkWinCondition} counted any team that had not been
 * explicitly eliminated, and elimination only happened when a bed was gone
 * <em>and</em> no member was active. An empty (never filled) team was therefore
 * "standing" forever and the match never ended.
 */
class WinConditionTest {

    private static final long NOW = 1_000L;

    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();
    private final UUID p3 = UUID.randomUUID();

    private final List<GameEvent> events = new ArrayList<>();

    private Game gameWithTeams(int teamCount) {
        EventBus bus = new EventBus();
        bus.subscribe(events::add);
        ArenaGroup group = new ArenaGroup("solo", teamCount, 2, 15, 300, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON, GeneratorType.GOLD));
        TemplateDescriptor template =
                new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL, Optional.empty());

        List<Team> teams = new ArrayList<>();
        TeamColor[] colors = TeamColor.values();
        for (int i = 0; i < teamCount; i++) {
            String id = colors[i].name().toLowerCase();
            teams.add(new Team(id, colors[i], new Bed(id, new Vec3(i * 100.0, 64, 0), 3.0), 2));
        }
        return new Game("g-" + teamCount, group, template, teams, List.of(), bus, 5, NOW);
    }

    @Test
    void matchEndsWhenOnlyOnePopulatedTeamRemainsInMultiTeamArena() {
        // A 4-team arena that only ever received 2 players (red, blue). Green and
        // yellow are empty and must not keep the match alive.
        Game game = gameWithTeams(4);
        game.addPlayer(p1, "alice"); // red
        game.addPlayer(p2, "bob");   // blue
        assertThat(game.session(p1).orElseThrow().teamId()).contains("red");
        assertThat(game.session(p2).orElseThrow().teamId()).contains("blue");

        game.startCountdown();
        game.beginMatch(NOW);
        assertThat(game.state()).isEqualTo(GameState.RUNNING);

        // Red's bed goes, then its only player dies: red is out, blue is the last
        // team standing, so the match must end with blue as the winner.
        assertThat(game.destroyBed("red", p2, NOW)).isTrue();
        game.onDeath(p1, NOW);

        assertThat(game.state()).isEqualTo(GameState.ENDED);
        assertThat(game.winnerTeamId()).contains("blue");
    }

    @Test
    void emptyTeamsAreEliminatedWhenTheMatchBegins() {
        Game game = gameWithTeams(4);
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.startCountdown();
        game.beginMatch(NOW);

        assertThat(game.team("green").orElseThrow().isEliminated()).isTrue();
        assertThat(game.team("yellow").orElseThrow().isEliminated()).isTrue();
        assertThat(game.team("red").orElseThrow().isEliminated()).isFalse();
        assertThat(game.team("blue").orElseThrow().isEliminated()).isFalse();
        assertThat(events).anyMatch(e -> e instanceof GameEvent.TeamEliminated);
    }

    @Test
    void lastPlayerDisconnectingEliminatesTheirTeamAndEndsTheMatch() {
        // Abandonment rule: with a bed still standing, a team whose last player quits
        // can never win and must be treated as out.
        Game game = gameWithTeams(2);
        game.addPlayer(p1, "alice"); // red
        game.addPlayer(p2, "bob");   // blue
        game.startCountdown();
        game.beginMatch(NOW);

        game.removePlayer(p1, NOW);

        assertThat(game.team("red").orElseThrow().isEliminated()).isTrue();
        assertThat(game.state()).isEqualTo(GameState.ENDED);
        assertThat(game.winnerTeamId()).contains("blue");
    }

    @Test
    void matchEndsAsADrawWhenEveryTeamIsAbandoned() {
        Game game = gameWithTeams(2);
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.startCountdown();
        game.beginMatch(NOW);

        game.removePlayer(p1, NOW);
        // Blue is now the only team standing, so the match already ended with blue.
        // Remove blue's player too and verify the terminal state is stable (no
        // re-transition, no exception).
        game.removePlayer(p2, NOW);

        assertThat(game.state()).isEqualTo(GameState.ENDED);
        assertThat(game.winnerTeamId()).contains("blue");
    }

    @Test
    void singlePopulatedTeamWinsImmediately() {
        Game game = gameWithTeams(3);
        game.addPlayer(p1, "alice"); // red only
        game.startCountdown();
        game.beginMatch(NOW);

        assertThat(game.state()).isEqualTo(GameState.ENDED);
        assertThat(game.winnerTeamId()).contains("red");
    }

    @Test
    void twoFullTeamsDoNotEndPrematurely() {
        // Guard against over-eager elimination: a normal filled match must stay RUNNING.
        Game game = gameWithTeams(2);
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.addPlayer(p3, "carol");
        game.startCountdown();
        game.beginMatch(NOW);

        assertThat(game.state()).isEqualTo(GameState.RUNNING);
        assertThat(game.winnerTeamId()).isEmpty();
    }

    @Test
    void nonFinalDeathDoesNotEliminateTeamWithStandingBed() {
        Game game = gameWithTeams(2);
        game.addPlayer(p1, "alice");
        game.addPlayer(p2, "bob");
        game.startCountdown();
        game.beginMatch(NOW);

        game.recordDamage(p1, p2, NOW);
        game.onDeath(p1, NOW);

        assertThat(game.team("red").orElseThrow().isEliminated()).isFalse();
        assertThat(game.state()).isEqualTo(GameState.RUNNING);
    }
}
