package dev.bedwars.core.domain;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import dev.bedwars.core.event.EventBus;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import dev.bedwars.core.upgrade.UpgradeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dragon Buff must translate into real dragons at sudden death: one per level the team
 * bought, and none for a team that is already out. This is the domain half of the
 * feature; the Spigot adapter spawns and drives the entities.
 */
class SuddenDeathDragonTest {

    private Game game(Team... teams) {
        ArenaGroup group = new ArenaGroup("solo", teams.length, 2, 15, 0, 0.0, 30.0, 3.0,
                List.of(GeneratorType.IRON));
        return new Game("g1", group,
                new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.LOCAL, Optional.empty()),
                List.of(teams), List.of(), new EventBus(), 5, 0L);
    }

    private static Team team(String id, TeamColor color, double x) {
        return new Team(id, color, new Bed(id, new Vec3(x, 64, 0), 3.0), 2);
    }

    @Test
    void aTeamThatBoughtDragonBuffBringsOneDragonPerLevel() {
        Team red = team("red", TeamColor.RED, 0);
        Team blue = team("blue", TeamColor.BLUE, 100);
        Game game = game(red, blue);

        assertThat(game.dragonsByTeam()).isEmpty();
        assertThat(UpgradeType.DRAGON_BUFF.maxLevel()).isEqualTo(1);

        red.upgrades().setLevel(UpgradeType.DRAGON_BUFF, 1);
        assertThat(game.dragonsByTeam()).containsExactly(Map.entry("red", 1));

        // Levels are clamped to the catalog, so asking for more still means one dragon.
        red.upgrades().setLevel(UpgradeType.DRAGON_BUFF, 5);
        assertThat(game.dragonsByTeam()).containsEntry("red", 1);
    }

    @Test
    void anEliminatedTeamBringsNoDragons() {
        Team red = team("red", TeamColor.RED, 0);
        Team blue = team("blue", TeamColor.BLUE, 100);
        Game game = game(red, blue);
        red.upgrades().setLevel(UpgradeType.DRAGON_BUFF, 1);

        red.eliminate();

        assertThat(game.dragonsByTeam()).doesNotContainKey("red");
    }

    @Test
    void bothTeamsMayBringDragons() {
        Team red = team("red", TeamColor.RED, 0);
        Team blue = team("blue", TeamColor.BLUE, 100);
        Game game = game(red, blue);
        red.upgrades().setLevel(UpgradeType.DRAGON_BUFF, 1);
        blue.upgrades().setLevel(UpgradeType.DRAGON_BUFF, 1);

        assertThat(game.dragonsByTeam()).containsOnlyKeys("red", "blue");
    }

    @Test
    void theDefaultCatalogSellsDragonBuff() {
        assertThat(UpgradeCatalog.defaults().tiers(UpgradeType.DRAGON_BUFF)).isNotEmpty();
    }
}
