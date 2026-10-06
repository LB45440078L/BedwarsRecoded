package dev.bedwars.spigot;

import dev.bedwars.api.dto.GamePhase;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.PodHeartbeat;
import dev.bedwars.api.service.TemplateSource;
import dev.bedwars.core.config.ArenaConfigLoader;
import dev.bedwars.core.config.ArenaDefinition;
import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GameState;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.TeamColor;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.event.EventBus;
import dev.bedwars.core.i18n.LanguageService;
import dev.bedwars.core.i18n.MessageCatalog;
import dev.bedwars.core.logging.CorrelationContext;
import dev.bedwars.core.manager.GameHost;
import dev.bedwars.core.manager.GameManager;
import dev.bedwars.core.persistence.Database;
import dev.bedwars.core.persistence.LeaderboardCache;
import dev.bedwars.core.persistence.Migrations;
import dev.bedwars.core.persistence.QuickBuyRepository;
import dev.bedwars.core.persistence.SchemaMigrator;
import dev.bedwars.core.persistence.StatsRepository;
import dev.bedwars.core.ranking.EloCalculator;
import dev.bedwars.core.ranking.MatchResultPersister;
import dev.bedwars.core.reporting.DeploymentMode;
import dev.bedwars.core.reporting.ReportingPolicy;
import dev.bedwars.core.reporting.WhitelistEnforcement;
import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.Price;
import dev.bedwars.core.shop.QuickBuyStore;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.shop.ShopCategory;
import dev.bedwars.core.shop.ShopItem;
import dev.bedwars.core.shop.ShopService;
import dev.bedwars.core.upgrade.TeamEffectCalculator;
import dev.bedwars.core.upgrade.TrapTriggerService;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import dev.bedwars.core.upgrade.UpgradeService;
import dev.bedwars.spigot.command.BedwarsCommand;
import dev.bedwars.spigot.config.PluginConfig;
import dev.bedwars.spigot.dragon.DragonService;
import dev.bedwars.spigot.effects.UpgradeEffectApplier;
import dev.bedwars.spigot.game.JoinService;
import dev.bedwars.spigot.gui.JoinMenu;
import dev.bedwars.spigot.listener.DomainEventBridge;
import dev.bedwars.spigot.listener.GameListener;
import dev.bedwars.spigot.listener.JoinSignListener;
import dev.bedwars.spigot.listener.NpcJoinListener;
import dev.bedwars.spigot.listener.ProtectionListener;
import dev.bedwars.spigot.listener.QuickBuyListener;
import dev.bedwars.spigot.listener.QuickBuySyncListener;
import dev.bedwars.spigot.listener.ShopListener;
import dev.bedwars.spigot.listener.SpectatorListener;
import dev.bedwars.spigot.listener.TrapTriggerListener;
import dev.bedwars.spigot.listener.UpgradeListener;
import dev.bedwars.spigot.listener.VoidKillListener;
import dev.bedwars.spigot.report.ControllerProbe;
import dev.bedwars.spigot.report.HttpPodReporter;
import dev.bedwars.spigot.scoreboard.ScoreboardRenderer;
import dev.bedwars.spigot.template.AspSlimeWorldBridge;
import dev.bedwars.spigot.template.AspSlimeWorldProvider;
import dev.bedwars.spigot.template.FallbackWorldProvider;
import dev.bedwars.spigot.template.LocalTemplateSource;
import dev.bedwars.spigot.template.S3TemplateSource;
import dev.bedwars.spigot.template.SlimeWorldProvider;
import dev.bedwars.spigot.util.TpsMeter;
import dev.bedwars.spigot.world.GameWorldService;
import com.infernalsuite.aswm.api.AdvancedSlimePaperAPI;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Game-server entrypoint.
 *
 * <p>A dedicated server hosts up to {@code arena.games-per-server} concurrent matches
 * (default 1). The {@link GameHost} owns their lifecycle; the {@link JoinService} seats
 * players; the {@link GameWorldService} gives each match its own world when more than one
 * is allowed. On boot the server loads its template and reports its free <em>match slots</em>
 * to the controller; on shutdown it reports DRAINING and flushes results.
 *
 * <p>Not {@code final}: test harnesses may subclass the plugin.
 */
public class BedwarsRecodedPlugin extends JavaPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-pod");

    private Database database;
    private StatsRepository statsRepository;
    private LeaderboardCache leaderboardCache;
    private EventBus eventBus;
    private GameManager gameManager;
    private GameHost host;
    private GameWorldService worlds;
    private JoinService joinService;
    private HttpPodReporter reporter;
    private PluginConfig config;

    private final MessageCatalog catalog = new MessageCatalog();
    private LanguageService languageService;
    private Shop shop;
    private final ShopService shopService = new ShopService();
    private final UpgradeCatalog defaultUpgradeCatalog = UpgradeCatalog.defaults();
    private UpgradeCatalog upgradeCatalog = defaultUpgradeCatalog;
    private final UpgradeService upgradeService = new UpgradeService();
    private final TpsMeter tpsMeter = new TpsMeter();
    private MatchResultPersister matchResultPersister;
    private SlimeWorldProvider worldProvider;
    private DeploymentMode effectiveMode = DeploymentMode.AUTO;
    private long startedAtMillis;
    private final Set<String> persistedGames = ConcurrentHashMap.newKeySet();
    private int lastReportedFreeSlots = -1;
    private final QuickBuyStore quickBuy = new QuickBuyStore();
    private final Map<UUID, Set<String>> ownedItems = new ConcurrentHashMap<>();
    private final ScoreboardRenderer scoreboardRenderer = new ScoreboardRenderer();
    private final TrapTriggerService trapTriggers = new TrapTriggerService(8.0, 5_000L);
    private QuickBuyRepository quickBuyRepository;
    private JoinMenu joinMenu;
    private DragonService dragonService;
    private List<String> startItems = List.of();

    private final Map<String, Integer> countdownRemaining = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            saveResource("arena.yml", false);
        } catch (IllegalArgumentException ignored) {
            // No bundled sample arena; a config-derived arena will be used.
        }
        this.config = PluginConfig.from(getConfig(), defaultServerId());
        this.effectiveMode = DeploymentMode.resolve(config.mode(), probeController(config));
        this.reporter = new HttpPodReporter(config.controllerBaseUrl(), new ReportingPolicy(
                effectiveMode.reportsToController(),
                config.disableReportingAfterFailures(),
                config.failureLogIntervalSeconds() * 1000L));
        logStartupSummary();
        applyWhitelistPolicy();
        this.eventBus = new EventBus();
        this.eventBus.subscribe(new DomainEventBridge(LOG, reporter, config.jsonLogs()));
        this.gameManager = new GameManager();
        this.languageService = new LanguageService(catalog);
        this.startedAtMillis = System.currentTimeMillis();

        TemplateDescriptor template = new TemplateDescriptor(
                config.templateName(), config.templateVersion(), config.templateSource(), Optional.empty());

        bootPersistence(config);

        ArenaDefinition arena = loadArenaDefinition(config);
        if (arena == null) {
            arena = defaultArena(config);
        }
        this.shop = arena.shop();
        this.startItems = arena.startItems();
        this.upgradeCatalog = arena.upgrades();

        Path templateDir = getDataFolder().toPath().resolve(config.localTemplatePath()).resolve(config.templateName());
        this.worlds = new GameWorldService(this, templateDir, config.gamesPerServer() > 1, LOG);
        this.host = new GameHost(arena, template, eventBus, gameManager,
                config.gamesPerServer(), 5, "match");
        this.joinService = new JoinService(host, worlds);
        this.dragonService = new DragonService(config.dragon(), LOG);

        this.joinMenu = new JoinMenu(joinService, host);
        registerListeners();
        getCommand("bedwars").setExecutor(new BedwarsCommand(this));
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, tpsMeter::tick, 0L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::applyTeamEffects, 40L, 40L);
        if (effectiveMode.reportsToController()) {
            getServer().getScheduler().runTaskTimer(this, this::sendHeartbeat, 200L,
                    Math.max(20L, config.heartbeatSeconds() * 20L));
        }
        getServer().getScheduler().runTaskTimer(this, this::refreshLeaderboards, 400L,
                Math.max(20L, config.leaderboardRefreshSeconds() * 20L));

        if (config.templateEnabled()) {
            loadTemplate(template, config);
        } else {
            LOG.info("template_disabled (template.enabled: false) - keeping the server's existing world");
        }
        reporter.reportReady(config.serverId(), config.arenaGroup(), template, host.gameCount() + " matches");
        reportCapacityIfChanged(true);
        LOG.info("server_ready server={} arena_group={} games_per_server={} shop={}",
                config.serverId(), config.arenaGroup(), config.gamesPerServer(), shop.id());
    }

    // ---- wiring ----------------------------------------------------------

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new GameListener(gameManager, joinService), this);
        pm.registerEvents(new ShopListener(), this);
        pm.registerEvents(new UpgradeListener(), this);
        pm.registerEvents(new VoidKillListener(gameManager), this);
        pm.registerEvents(new ProtectionListener(gameManager), this);
        pm.registerEvents(new SpectatorListener(gameManager, this), this);
        pm.registerEvents(new JoinSignListener(joinService), this);
        pm.registerEvents(new NpcJoinListener(joinService), this);
        pm.registerEvents(new TrapTriggerListener(gameManager, trapTriggers), this);
        pm.registerEvents(new QuickBuyListener(), this);
        pm.registerEvents(joinMenu, this);
        if (quickBuyRepository != null) {
            pm.registerEvents(new QuickBuySyncListener(quickBuyRepository, quickBuy), this);
        }
    }

    /** Applies each participant's team upgrade effects to their gear and buffs. */
    private void applyTeamEffects() {
        for (Player player : getServer().getOnlinePlayers()) {
            gameManager.byPlayer(player.getUniqueId()).ifPresent(game -> {
                if (game.state() != GameState.RUNNING && game.state() != GameState.SUDDEN_DEATH) {
                    return;
                }
                game.session(player.getUniqueId()).flatMap(session -> session.teamId().flatMap(game::team))
                        .ifPresent(team -> {
                            var effects = TeamEffectCalculator.forTeam(team.upgrades());
                            boolean inBase = new Vec3(player.getLocation().getX(), player.getLocation().getY(),
                                    player.getLocation().getZ()).isWithin(team.bed().position(), 8.0);
                            UpgradeEffectApplier.apply(player, effects, inBase);
                        });
            });
        }
    }

    private static String rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static String defaultServerId() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            hostname = System.getenv("COMPUTERNAME");
        }
        if (hostname == null || hostname.isBlank()) {
            try {
                hostname = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                hostname = "local";
            }
        }
        return "server-" + hostname;
    }

    private boolean probeController(PluginConfig cfg) {
        if (cfg.mode() == DeploymentMode.POD) {
            return true;
        }
        if (cfg.mode() == DeploymentMode.STANDALONE) {
            return false;
        }
        boolean reachable = ControllerProbe.isReachable(cfg.controllerBaseUrl(), java.time.Duration.ofSeconds(2));
        LOG.info("deployment_mode_auto controller={} reachable={}", cfg.controllerBaseUrl(), reachable);
        return reachable;
    }

    private void applyWhitelistPolicy() {
        if (!WhitelistEnforcement.shouldDisable(config.whitelistEnforcement(), effectiveMode)) {
            return;
        }
        if (getServer().hasWhitelist()) {
            getServer().setWhitelist(false);
            LOG.info("whitelist_disabled it was enabled (by the server's own default or by an operator); "
                    + "a game server accepts the players routed to it "
                    + "(server.force-whitelist-off={} mode={})", config.whitelistEnforcement(), effectiveMode);
        } else {
            LOG.info("whitelist_ok already off (mode={})", effectiveMode);
        }
    }

    private void logStartupSummary() {
        String reporting = effectiveMode.reportsToController()
                ? reporter.policy().describe()
                : "not used (not a pod)";
        LOG.info("bedwars_setup mode={} (configured {}) controller_reporting={} controller_url={}",
                effectiveMode, config.mode(), reporting, config.controllerBaseUrl());
        LOG.info("bedwars_setup server_id={} arena_group={} teams={}x{} games_per_server={} template={} persistence={} json_logs={}",
                config.serverId(), config.arenaGroup(), config.teamCount(), config.playersPerTeam(),
                config.gamesPerServer(),
                config.templateEnabled() ? config.templateName() + "@" + config.templateVersion() + "("
                        + config.templateSource() + ")" : "disabled",
                config.persistenceEnabled() ? config.database().host() : "disabled",
                config.jsonLogs());
    }

    private void giveStartItems(Game game) {
        if (startItems.isEmpty()) {
            return;
        }
        for (UUID uuid : game.sessions().keySet()) {
            Player player = getServer().getPlayer(uuid);
            if (player == null) {
                continue;
            }
            for (String materialName : startItems) {
                Material material = Material.matchMaterial(materialName);
                if (material != null) {
                    player.getInventory().addItem(new ItemStack(material));
                }
            }
        }
    }

    private void bootPersistence(PluginConfig cfg) {
        if (!cfg.persistenceEnabled()) {
            LOG.info("persistence_disabled (persistence.enabled: false) - running without stats");
            return;
        }
        try {
            this.database = new Database(cfg.database(), LOG);
            new SchemaMigrator(database, LOG).migrate(Migrations.all());
            this.statsRepository = new StatsRepository(database, LOG, 1000);
            this.leaderboardCache = new LeaderboardCache(database, LOG, 100);
            this.quickBuyRepository = new QuickBuyRepository(database, LOG);
            this.matchResultPersister = new MatchResultPersister(new EloCalculator(cfg.kFactor()));
            LOG.info("persistence_ready host={}:{} database={}", cfg.database().host(), cfg.database().port(),
                    cfg.database().database());
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("persistence_unavailable running without stats ({}). "
                    + "Set persistence.enabled: false to silence this.", rootCause(e));
        }
    }

    /** Persists each finished match's stat deltas and ELO exactly once. */
    private void persistResults() {
        if (matchResultPersister == null || statsRepository == null) {
            return;
        }
        for (Game game : host.games()) {
            if (!game.state().isTerminal() || !persistedGames.add(game.id())) {
                continue;
            }
            matchResultPersister.persist(game.results(), statsRepository)
                    .thenRun(() -> LOG.info("match_results_persisted game={}", game.id()))
                    .exceptionally(error -> {
                        LOG.error("Failed to persist match results for {}", game.id(), error);
                        return null;
                    });
        }
    }

    /** Reports free match slots (only when they change) so the controller can route players. */
    private void reportCapacityIfChanged(boolean force) {
        if (host == null || !effectiveMode.reportsToController()) {
            return;
        }
        int free = host.freeSlots();
        if (!force && free == lastReportedFreeSlots) {
            return;
        }
        lastReportedFreeSlots = free;
        reporter.reportCapacity(config.serverId(), free, host.maxGames());
    }

    private void sendHeartbeat() {
        int players = 0;
        for (Game game : host.games()) {
            players += game.playerCount();
        }
        PodHeartbeat beat = new PodHeartbeat(config.serverId(), "matches=" + host.gameCount(), tpsMeter.tps(),
                players, phaseFor(host), System.currentTimeMillis() - startedAtMillis);
        reporter.heartbeat(beat);
        reportCapacityIfChanged(false);
        LOG.info("heartbeat server={} matches={} in_progress={} free_slots={} tps={} players={}",
                beat.podId(), host.gameCount(), host.inProgressGames(), host.freeSlots(),
                String.format("%.1f", beat.tps()), players);
    }

    private static GamePhase phaseFor(GameHost host) {
        boolean running = false;
        boolean countdown = false;
        for (Game game : host.games()) {
            switch (game.state()) {
                case RUNNING, SUDDEN_DEATH -> running = true;
                case COUNTDOWN -> countdown = true;
                default -> {
                }
            }
        }
        if (running) {
            return GamePhase.RUNNING;
        }
        return countdown ? GamePhase.COUNTDOWN : GamePhase.WAITING;
    }

    private void refreshLeaderboards() {
        if (leaderboardCache != null) {
            leaderboardCache.refresh();
        }
    }

    public void reloadConfiguration() {
        reloadConfig();
        PluginConfig fresh = PluginConfig.from(getConfig(), config.serverId());
        this.config = fresh;
        dragonService.updateSettings(fresh.dragon());
        ArenaDefinition arena = loadArenaDefinition(fresh);
        if (arena != null) {
            this.shop = arena.shop();
            this.startItems = arena.startItems();
            this.upgradeCatalog = arena.upgrades();
        }
        LOG.info("config_reloaded shop={} upgrades={} startItems={} (games-per-server applies after restart)",
                shop.id(), upgradeCatalog == defaultUpgradeCatalog ? "defaults" : "arena.yml", startItems.size());
    }

    private void loadTemplate(TemplateDescriptor template, PluginConfig config) {
        TemplateSource source = switch (config.templateSource()) {
            case S3 -> new S3TemplateSource(
                    URI.create(env("BEDWARS_TEMPLATE_S3_ENDPOINT", getConfig().getString("template.s3.endpoint", "http://minio:9000"))),
                    env("BEDWARS_TEMPLATE_S3_BUCKET", getConfig().getString("template.s3.bucket", "bedwars-templates")),
                    env("BEDWARS_TEMPLATE_S3_REGION", getConfig().getString("template.s3.region", "us-east-1")),
                    env("BEDWARS_TEMPLATE_S3_ACCESS_KEY", getConfig().getString("template.s3.access-key", "")),
                    env("BEDWARS_TEMPLATE_S3_SECRET_KEY", getConfig().getString("template.s3.secret-key", "")));
            case LOCAL -> new LocalTemplateSource(getDataFolder().toPath().resolve(config.localTemplatePath()));
        };
        source.materialise(template, getDataFolder().toPath().resolve("staging"))
                .thenAccept(path -> {
                    LOG.info("template_materialised template={} path={} productionReady={}",
                            template.coordinate(), path, source.productionReady());
                    loadWorld(template, path);
                })
                .exceptionally(error -> {
                    LOG.warn("template_load_failed template={} reason={}. "
                                    + "Provide the world under template.local-path, or set template.enabled: false. "
                                    + "The server keeps its current world until then.",
                            template.coordinate(), rootCause(error));
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("template_load_failed detail", error);
                    }
                    return null;
                });
    }

    private void loadWorld(TemplateDescriptor template, Path archive) {
        SlimeWorldProvider provider = worldProvider();
        try {
            boolean ok = provider.load(archive, template.name(), "match");
            if (!ok) {
                LOG.warn("world_load_incomplete loader={} template={}", provider.backend(), template.coordinate());
            }
        } catch (Exception e) {
            LOG.error("world_load_failed loader={} template={}", provider.backend(), template.coordinate(), e);
        }
    }

    private SlimeWorldProvider worldProvider() {
        if (worldProvider == null) {
            worldProvider = resolveAspProvider();
            if (worldProvider == null) {
                worldProvider = new FallbackWorldProvider(
                        getDataFolder().toPath().resolve("templates").resolve(config.templateName()), LOG);
            }
        }
        return worldProvider;
    }

    private SlimeWorldProvider resolveAspProvider() {
        try {
            if (getServer().getPluginManager().getPlugin("AdvancedSlimePaper") == null) {
                return null;
            }
            AdvancedSlimePaperAPI asp = AdvancedSlimePaperAPI.instance();
            String dataSource = env("BEDWARS_SLIME_DATASOURCE",
                    getConfig().getString("template.slime-datasource", "file"));
            SlimeLoader loader = resolveAspLoader(dataSource);
            if (asp == null || loader == null) {
                return null;
            }
            return new AspSlimeWorldProvider(new AspSlimeWorldBridge(asp, loader, dataSource, null), LOG);
        } catch (Throwable t) {
            LOG.warn("asp_unavailable; using the non-production world loader: {}", t.toString());
            return null;
        }
    }

    private SlimeLoader resolveAspLoader(String dataSource) throws Exception {
        org.bukkit.plugin.Plugin asp = getServer().getPluginManager().getPlugin("AdvancedSlimePaper");
        if (asp == null) {
            return null;
        }
        Object loader = asp.getClass().getMethod("getLoader", String.class).invoke(asp, dataSource);
        return loader instanceof SlimeLoader slime ? slime : null;
    }

    private ArenaDefinition loadArenaDefinition(PluginConfig config) {
        Path arenaFile = getDataFolder().toPath().resolve("arena.yml");
        if (!Files.isRegularFile(arenaFile)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(arenaFile)) {
            return new ArenaConfigLoader().load(in);
        } catch (Exception e) {
            LOG.error("Failed to load arena.yml; falling back to a config-derived arena", e);
            return null;
        }
    }

    /** Arena built from config when no arena.yml is present. */
    private ArenaDefinition defaultArena(PluginConfig config) {
        ArenaGroup group = new ArenaGroup(config.arenaGroup(), config.teamCount(), config.playersPerTeam(),
                config.countdownSeconds(), config.suddenDeathAfterSeconds(), config.voidY(),
                config.islandRadius(), config.bedProtectionRadius(),
                List.of(GeneratorType.IRON, GeneratorType.GOLD, GeneratorType.DIAMOND, GeneratorType.EMERALD));
        Map<String, Vec3> beds = new LinkedHashMap<>();
        TeamColor[] colors = TeamColor.values();
        for (int i = 0; i < group.teamCount(); i++) {
            String id = colors[i % colors.length].name().toLowerCase();
            beds.put(id, new Vec3(i * 40.0, 64.0, 0.0));
        }
        return new ArenaDefinition(group, beds, Map.of(), List.of(), defaultShop(), List.of(),
                defaultUpgradeCatalog);
    }

    // ---- tick loop -------------------------------------------------------

    private void tick() {
        for (Game game : host.games()) {
            CorrelationContext.run(game.id(), config.serverId(), () -> tickGame(game));
        }
        dragonService.tick(host.games());
        updateScoreboards();
        persistResults();
        pruneFinished();
    }

    private void tickGame(Game game) {
        if (game.state().isTerminal()) {
            return;
        }
        long now = System.currentTimeMillis();
        spawnGeneratorItems(game, now);
        handleCountdown(game);
        handleSuddenDeath(game, now);
    }

    private void spawnGeneratorItems(Game game, long now) {
        World world = worlds.worldFor(game.id());
        if (world == null) {
            return;
        }
        for (Game.GeneratorSpawn spawn : game.tickGenerators(now)) {
            Material material = materialFor(spawn.type());
            if (material != null) {
                world.dropItemNaturally(new Location(world,
                        spawn.position().x(), spawn.position().y(), spawn.position().z()),
                        new ItemStack(material, spawn.itemCount()));
            }
        }
    }

    private void handleCountdown(Game game) {
        if (game.state() == GameState.WAITING) {
            if (game.playerCount() >= game.group().teamCount()) {
                game.startCountdown();
                countdownRemaining.put(game.id(), game.group().countdownSeconds());
                broadcastTo(game, "&eMatch starting in &c" + game.group().countdownSeconds() + "&e...");
            }
            return;
        }
        if (game.state() == GameState.COUNTDOWN) {
            int remaining = countdownRemaining.merge(game.id(), -1, Integer::sum);
            if (remaining <= 0) {
                game.beginMatch(System.currentTimeMillis());
                giveStartItems(game);
                broadcastTo(game, "&a&lThe match has begun!");
            } else if (remaining <= 5) {
                broadcastTo(game, "&eStarting in &c" + remaining + "&e...");
            }
        }
    }

    private void handleSuddenDeath(Game game, long now) {
        int after = game.group().suddenDeathAfterSecs();
        if (game.state() == GameState.RUNNING && after > 0 && game.startedAtMillis() > 0
                && now - game.startedAtMillis() >= after * 1000L) {
            game.enterSuddenDeath(now);
            broadcastTo(game, "&4Sudden death! All beds have been destroyed.");
            int dragons = dragonService.spawnForGame(game, worlds.worldFor(game.id()));
            if (dragons > 0) {
                broadcastTo(game, "&5Dragons are unleashed - they tear up the map and hurl you back!");
            }
        }
    }

    private void updateScoreboards() {
        for (Player player : getServer().getOnlinePlayers()) {
            gameManager.byPlayer(player.getUniqueId())
                    .ifPresent(game -> scoreboardRenderer.update(player, game));
        }
    }

    /** A match that has finished is returned to the pool and its world reclaimed. */
    private void pruneFinished() {
        List<String> finished = host.games().stream()
                .filter(game -> game.state().isTerminal())
                .map(Game::id)
                .toList();
        if (finished.isEmpty()) {
            return;
        }
        for (String id : finished) {
            dragonService.clearGame(id);
            host.byId(id).ifPresent(game -> {
                broadcastTo(game, "&eThis match has ended.");
                for (UUID uuid : game.sessions().keySet()) {
                    scoreboardRenderer.forget(uuid);
                }
            });
        }
        host.pruneFinished();
        finished.forEach(worlds::release);
        reportCapacityIfChanged(false);
    }

    private void broadcastTo(Game game, String legacyMessage) {
        String coloured = ChatColor.translateAlternateColorCodes('&', legacyMessage);
        for (UUID uuid : game.sessions().keySet()) {
            Player player = getServer().getPlayer(uuid);
            if (player != null) {
                player.sendMessage(coloured);
            }
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

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ---- default shop ----------------------------------------------------

    private static Shop defaultShop() {
        ShopCategory blocks = new ShopCategory("blocks", "Blocks", 0, "WHITE_WOOL", List.of(
                new ShopItem("wool", "blocks", "Wool", 0, "WHITE_WOOL", 16,
                        Price.of(Currency.IRON, 4), List.of(), false, false, Optional.empty(), 0),
                new ShopItem("wood", "blocks", "Wood", 1, "OAK_PLANKS", 16,
                        Price.of(Currency.GOLD, 4), List.of(), false, false, Optional.empty(), 0)));
        ShopCategory combat = new ShopCategory("combat", "Combat", 1, "IRON_SWORD", List.of(
                new ShopItem("sword-stone", "combat", "Stone Sword", 0, "STONE_SWORD", 1,
                        Price.of(Currency.GOLD, 10), List.of(), true, true, Optional.of("sword"), 1),
                new ShopItem("sword-iron", "combat", "Iron Sword", 1, "IRON_SWORD", 1,
                        Price.of(Currency.GOLD, 7), List.of(), true, true, Optional.of("sword"), 2)));
        ShopCategory armour = new ShopCategory("armour", "Armour", 2, "CHAINMAIL_CHESTPLATE", List.of(
                new ShopItem("armour-chain", "armour", "Chainmail Armour", 0, "CHAINMAIL_CHESTPLATE", 1,
                        Price.of(Currency.IRON, 40), List.of(), true, false, Optional.empty(), 0)));
        return new Shop("default", "Item Shop", List.of(blocks, combat, armour));
    }

    // ---- accessors -------------------------------------------------------

    public GameHost host() {
        return host;
    }

    public GameManager gameManager() {
        return gameManager;
    }

    public JoinService joinService() {
        return joinService;
    }

    public GameWorldService worlds() {
        return worlds;
    }

    public LanguageService languageService() {
        return languageService;
    }

    public Shop shop() {
        return shop;
    }

    public ShopService shopService() {
        return shopService;
    }

    public UpgradeCatalog upgradeCatalog() {
        return upgradeCatalog;
    }

    public UpgradeService upgradeService() {
        return upgradeService;
    }

    public QuickBuyStore quickBuy() {
        return quickBuy;
    }

    public TrapTriggerService trapTriggers() {
        return trapTriggers;
    }

    public JoinMenu joinMenu() {
        return joinMenu;
    }

    public Set<String> ownedItems(UUID player) {
        return ownedItems.computeIfAbsent(player, key -> ConcurrentHashMap.newKeySet());
    }

    @Override
    public void onDisable() {
        if (dragonService != null) {
            dragonService.shutdown();
        }
        if (host != null) {
            int players = 0;
            for (Game game : host.games()) {
                players += game.activePlayerCount();
                if (!game.state().isTerminal()) {
                    game.abort();
                }
            }
            persistResults();
            // Release every match world so a restart does not leave orphans behind.
            host.games().forEach(game -> worlds.release(game.id()));
            reporter.reportDraining(config.serverId(), host.gameCount() + " matches", players);
        }
        if (statsRepository != null) {
            statsRepository.close();
        }
        if (quickBuyRepository != null) {
            quickBuyRepository.close();
        }
        if (leaderboardCache != null) {
            leaderboardCache.close();
        }
        if (database != null) {
            database.close();
        }
        LOG.info("game_server_shutdown complete");
    }
}
