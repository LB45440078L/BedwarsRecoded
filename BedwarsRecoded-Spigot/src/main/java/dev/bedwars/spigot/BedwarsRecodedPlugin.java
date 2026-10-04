package dev.bedwars.spigot;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.PodReporter;
import dev.bedwars.api.service.TemplateSource;
import dev.bedwars.core.config.DatabaseConfig;
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
import dev.bedwars.core.manager.GameManager;
import dev.bedwars.core.persistence.Database;
import dev.bedwars.core.persistence.LeaderboardCache;
import dev.bedwars.core.persistence.Migrations;
import dev.bedwars.core.persistence.SchemaMigrator;
import dev.bedwars.core.persistence.StatsRepository;
import dev.bedwars.spigot.command.BedwarsCommand;
import dev.bedwars.spigot.config.PluginConfig;
import dev.bedwars.spigot.listener.DomainEventBridge;
import dev.bedwars.spigot.listener.GameListener;
import dev.bedwars.spigot.report.HttpPodReporter;
import dev.bedwars.spigot.template.LocalTemplateSource;
import dev.bedwars.spigot.template.S3TemplateSource;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Game-pod entrypoint. One pod = one match. On boot the plugin loads its
 * template, wires Core services, and reports READY to the controller. On
 * shutdown it reports DRAINING and flushes results. There is no persistent
 * arena state on disk (constraint #1); the pod itself is the reset (constraint #2).
 */
public final class BedwarsRecodedPlugin extends JavaPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-pod");

    private Database database;
    private StatsRepository statsRepository;
    private LeaderboardCache leaderboardCache;
    private EventBus eventBus;
    private GameManager gameManager;
    private PodReporter reporter;
    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginConfig config = PluginConfig.from(getConfig(), "pod-" + UUID.randomUUID());

        this.reporter = new HttpPodReporter(config.controllerBaseUrl());
        this.eventBus = new EventBus();
        this.eventBus.subscribe(new DomainEventBridge(LOG, reporter));
        this.gameManager = new GameManager();

        TemplateDescriptor template = new TemplateDescriptor(
                config.templateName(), config.templateVersion(), config.templateSource(), java.util.Optional.empty());

        bootPersistence(config.database());

        this.game = buildGame(config, template);
        gameManager.register(game);

        getServer().getPluginManager().registerEvents(new GameListener(gameManager), this);
        getCommand("bedwars").setExecutor(new BedwarsCommand(gameManager, game));

        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        loadTemplate(template, config);
        reporter.reportReady(config.serverId(), template, game.id());
        LOG.info("pod_ready pod={} game={} template={}", config.serverId(), game.id(), template.coordinate());
    }

    private void bootPersistence(DatabaseConfig databaseConfig) {
        try {
            this.database = new Database(databaseConfig, LOG);
            new SchemaMigrator(database, LOG).migrate(Migrations.all());
            this.statsRepository = new StatsRepository(database, LOG, 1000);
            this.leaderboardCache = new LeaderboardCache(database, LOG, 100);
        } catch (RuntimeException e) {
            // A game pod can still run a match without stats; the controller will
            // mark it degraded. Never crash the pod over a DB hiccup at boot.
            LOG.error("Persistence unavailable; running without stats", e);
        }
    }

    private void loadTemplate(TemplateDescriptor template, PluginConfig config) {
        TemplateSource source = switch (config.templateSource()) {
            case S3 -> new S3TemplateSource(
                    URI.create(getConfig().getString("template.s3.endpoint", "http://minio:9000")),
                    getConfig().getString("template.s3.bucket", "bedwars-templates"),
                    getConfig().getString("template.s3.region", "us-east-1"));
            case LOCAL -> new LocalTemplateSource(getDataFolder().toPath().resolve(config.localTemplatePath()));
        };
        source.materialise(template, getDataFolder().toPath().resolve("staging"))
                .thenAccept(path -> LOG.info("template_materialised template={} path={} productionReady={}",
                        template.coordinate(), path, source.productionReady()))
                .exceptionally(error -> {
                    LOG.error("template_load_failed template={}", template.coordinate(), error);
                    return null;
                });
    }

    /**
     * Builds the single match this pod hosts. Bed/spawn/generator coordinates are
     * normally injected by the template loader (AdvancedSlimePaper adapter); this
     * scaffold derives teams from config and places beds at the template origin.
     */
    private Game buildGame(PluginConfig config, TemplateDescriptor template) {
        ArenaGroup group = new ArenaGroup(
                config.arenaGroup(), config.teamCount(), config.playersPerTeam(),
                config.countdownSeconds(), config.suddenDeathAfterSeconds(),
                config.voidY(), config.islandRadius(), config.bedProtectionRadius(),
                List.of(GeneratorType.IRON, GeneratorType.GOLD, GeneratorType.DIAMOND, GeneratorType.EMERALD));

        List<Team> teams = new ArrayList<>();
        TeamColor[] colors = TeamColor.values();
        for (int i = 0; i < config.teamCount(); i++) {
            TeamColor color = colors[i % colors.length];
            String id = color.name().toLowerCase();
            Vec3 bedPos = new Vec3(i * 40.0, 64.0, 0.0);
            teams.add(new Team(id, color, new Bed(id, bedPos, config.bedProtectionRadius()), config.playersPerTeam()));
        }

        List<Generator> generators = new ArrayList<>();
        for (Team team : teams) {
            generators.add(new Generator(team.id() + "-iron", GeneratorType.IRON, GeneratorTier.I,
                    team.bed().position(), System.currentTimeMillis()));
            generators.add(new Generator(team.id() + "-gold", GeneratorType.GOLD, GeneratorTier.I,
                    team.bed().position(), System.currentTimeMillis()));
        }
        generators.add(new Generator("center-diamond", GeneratorType.DIAMOND, GeneratorTier.I,
                new Vec3(0, 64, 0), System.currentTimeMillis()));
        generators.add(new Generator("center-emerald", GeneratorType.EMERALD, GeneratorTier.I,
                new Vec3(0, 64, 0), System.currentTimeMillis()));

        return new Game("game-" + UUID.randomUUID(), group, template, teams, generators, eventBus, 5,
                System.currentTimeMillis());
    }

    private void tick() {
        if (game.state().isTerminal()) {
            return;
        }
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().getFirst();
        if (world == null) {
            return;
        }
        for (Game.GeneratorSpawn spawn : game.tickGenerators(System.currentTimeMillis())) {
            Material material = materialFor(spawn.type());
            if (material == null) {
                continue;
            }
            world.dropItemNaturally(
                    new org.bukkit.Location(world, spawn.position().x(), spawn.position().y(), spawn.position().z()),
                    new ItemStack(material, spawn.itemCount()));
        }
    }

    private static Material materialFor(GeneratorType type) {
        return switch (type) {
            case IRON -> Material.IRON_INGOT;
            case GOLD -> Material.GOLD_INGOT;
            case DIAMOND -> Material.DIAMOND;
            case EMERALD -> Material.EMERALD;
        };
    }

    @Override
    public void onDisable() {
        if (game != null) {
            reporter.reportDraining(game.id(), game.activePlayerCount());
            if (game.state() == dev.bedwars.core.domain.GameState.RUNNING) {
                game.endGame(game.winnerTeamId(), System.currentTimeMillis());
            }
            reporter.reportGameEnded(game.results());
        }
        if (statsRepository != null) {
            statsRepository.close();
        }
        if (leaderboardCache != null) {
            leaderboardCache.close();
        }
        if (database != null) {
            database.close();
        }
        LOG.info("pod_shutdown complete");
    }
}