package dev.bedwars.core.manager;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.core.config.ArenaDefinition;
import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Bed;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.Generator;
import dev.bedwars.core.domain.GeneratorTier;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.TeamColor;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.event.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Instantiates one {@link Game} from an {@link ArenaDefinition}. Extracted from the
 * Spigot bootstrap so the same construction rule is reused for every match a server
 * hosts (a server may host many) and can be unit-tested without Bukkit.
 */
public final class GameFactory {

    private GameFactory() {
    }

    public static Game create(String gameId,
                              ArenaDefinition arena,
                              TemplateDescriptor template,
                              EventBus bus,
                              int respawnDelaySeconds,
                              long nowMillis) {
        ArenaGroup group = arena.group();

        List<Team> teams = new ArrayList<>();
        int index = 0;
        for (var entry : arena.teamBeds().entrySet()) {
            String teamId = entry.getKey();
            TeamColor color = colorFor(teamId, index++);
            teams.add(new Team(teamId, color,
                    new Bed(teamId, entry.getValue(), group.bedProtectionRadius()),
                    group.playersPerTeam()));
        }

        List<Generator> generators = new ArrayList<>();
        for (var spec : arena.generators()) {
            generators.add(new Generator(spec.id(), spec.type(), spec.tier(), spec.position(), nowMillis));
        }

        // Every team needs its own iron and gold forge even if the arena file omits them.
        for (Team team : teams) {
            boolean has = generators.stream().anyMatch(g -> g.id().startsWith(team.id() + "-"));
            if (!has) {
                generators.add(new Generator(team.id() + "-iron", GeneratorType.IRON, GeneratorTier.I,
                        team.bed().position(), nowMillis));
                generators.add(new Generator(team.id() + "-gold", GeneratorType.GOLD, GeneratorTier.I,
                        team.bed().position(), nowMillis));
            }
        }
        if (generators.stream().noneMatch(g -> g.type() == GeneratorType.DIAMOND)) {
            generators.add(new Generator("center-diamond", GeneratorType.DIAMOND, GeneratorTier.I,
                    new Vec3(0, 64, 0), nowMillis));
        }
        if (generators.stream().noneMatch(g -> g.type() == GeneratorType.EMERALD)) {
            generators.add(new Generator("center-emerald", GeneratorType.EMERALD, GeneratorTier.I,
                    new Vec3(0, 64, 0), nowMillis));
        }

        return new Game(gameId, group, template, teams, generators, bus, respawnDelaySeconds, nowMillis);
    }

    /** Maps a team id to a colour, matching by name first and falling back by position. */
    public static TeamColor colorFor(String teamId, int index) {
        for (TeamColor color : TeamColor.values()) {
            if (color.name().equalsIgnoreCase(teamId)) {
                return color;
            }
        }
        return TeamColor.values()[index % TeamColor.values().length];
    }
}
