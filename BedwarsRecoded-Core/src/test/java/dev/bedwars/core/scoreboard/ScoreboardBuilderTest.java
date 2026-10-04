package dev.bedwars.core.scoreboard;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Bed;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.TeamColor;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.event.EventBus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreboardBuilderTest {

    @Test
    void rendersPlayerStatsAndTeamStatus() {
        ArenaGroup group = new ArenaGroup("solo", 2, 2, 15, 300, 0.0, 30.0, 3.0, List.of(GeneratorType.IRON));
        Team red = new Team("red", TeamColor.RED, new Bed("red", new Vec3(0, 64, 0), 3.0), 2);
        Team blue = new Team("blue", TeamColor.BLUE, new Bed("blue", new Vec3(50, 64, 0), 3.0), 2);
        Game game = new Game("g", group, new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL,
                Optional.empty()), List.of(red, blue), List.of(), new EventBus(), 5, 0L);

        UUID player = UUID.randomUUID();
        game.addPlayer(player, "alice");
        game.startCountdown();
        game.beginMatch(1_000L);

        List<String> lines = new ScoreboardBuilder().build(game, player);
        assertThat(lines).anyMatch(line -> line.contains("BEDWARS"));
        assertThat(lines).anyMatch(line -> line.contains("Kills:"));
        assertThat(lines).anyMatch(line -> line.contains("Your bed:"));
        assertThat(lines).anyMatch(line -> line.contains("red"));
        assertThat(lines).anyMatch(line -> line.contains("blue"));
    }
}