package dev.bedwars.spigot;

import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.service.PodReporter;
import dev.bedwars.api.service.TemplateSource;
import dev.bedwars.core.config.ArenaConfigLoader;
import dev.bedwars.core.config.ArenaDefinition;
import dev.bedwars.core.config.DatabaseConfig;
import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.Bed;
import dev.bedwars.core.domain.Game;
import dev.bedwars.core.domain.GameState;
import dev.bedwars.core.domain.Generator;
import dev.bedwars.core.domain.GeneratorTier;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Team;
import dev.bedwars.core.domain.TeamColor;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.event.EventBus;
import dev.bedwars.core.i18n.LanguageService;
import dev.bedwars.core.i18n.MessageCatalog;
import dev.bedwars.core.manager.GameManager;
import dev.bedwars.core.persistence.Database;
import dev.bedwars.core.persistence.LeaderboardCache;
import dev.bedwars.core.persistence.Migrations;
import dev.bedwars.core.persistence.SchemaMigrator;
import dev.bedwars.core.persistence.StatsRepository;
import dev.bedwars.core.persistence.QuickBuyRepository;
import dev.bedwars.core.upgrade.TeamEffectCalculator;
import dev.bedwars.core.upgrade.TrapTriggerService;
import dev.bedwars.spigot.effects.UpgradeEffectApplier;
import dev.bedwars.spigot.gui.JoinMenu;
import dev.bedwars.spigot.listener.NpcJoinListener;
import dev.bedwars.spigot.listener.QuickBuyListener;
import dev.bedwars.spigot.listener.QuickBuySyncListener;
import dev.bedwars.spigot.listener.TrapTriggerListener;
import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.Price;
import dev.bedwars.core.shop.QuickBuyStore;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.shop.ShopCategory;
import dev.bedwars.core.shop.ShopItem;
import dev.bedwars.core.shop.ShopService;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import dev.bedwars.core.upgrade.UpgradeService;
import dev.bedwars.core.logging.CorrelationContext;
import dev.bedwars.core.logging.StructuredLog;
import dev.bedwars.core.ranking.EloCalculator;
import dev.bedwars.core.ranking.MatchResultPersister;
import dev.bedwars.api.service.PodHeartbeat;
import dev.bedwars.spigot.util.TpsMeter;
import dev.bedwars.spigot.command.BedwarsCommand;
import dev.bedwars.spigot.config.PluginConfig;
import dev.bedwars.spigot.listener.DomainEventBridge;
import dev.bedwars.spigot.listener.GameListener;
import dev.bedwars.spigot.listener.JoinSignListener;
import dev.bedwars.spigot.listener.ProtectionListener;
import dev.bedwars.spigot.listener.ShopListener;
import dev.bedwars.spigot.listener.SpectatorListener;
import dev.bedwars.spigot.listener.UpgradeListener;
import dev.bedwars.spigot.listener.VoidKillListener;
import dev.bedwars.spigot.report.HttpPodReporter;
import dev.bedwars.spigot.scoreboard.ScoreboardRenderer;
import dev.bedwars.spigot.template.AspSlimeWorldBridge;
import dev.bedwars.spigot.template.AspSlimeWorldProvider;
import dev.bedwars.spigot.template.FallbackWorldProvider;
import dev.bedwars.spigot.template.LocalTemplateSource;
import dev.bedwars.spigot.template.S3TemplateSource;
import dev.bedwars.spigot.template.SlimeWorldProvider;
import com.infernalsuite.aswm.api.AdvancedSlimePaperAPI;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Game-pod entrypoint. One pod = one match. On boot the plugin loads its template,
 * wires Core services, and reports READY to the controller. On shutdown it reports
 * DRAINING and flushes results. There is no persistent arena state on disk
 * (constraint #1); the pod itself is the reset (constraint #2).
 *
 * <p>Not {@code final}: MockBukkit subclasses the plugin to load it in tests.
 */
public class BedwarsRecodedPlugin extends JavaPlugin {

    private static final Logger LOG = LoggerFactory.getLogger("bedwars-pod");

    private Database database;
    private StatsRepository statsRepository;
    private LeaderboardCache leaderboardCache;
    private EventBus eventBus;
    private GameManager gameManager;
    private PodReporter reporter;
    private Game game;
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
    private long startedAtMillis;
    private boolean resultsPersisted;
    private final QuickBuyStore quickBuy = new QuickBuyStore();
    private final Map<UUID, Set<String>> ownedItems = new ConcurrentHashMap<>();
    private final ScoreboardRenderer scoreboardRenderer = new ScoreboardRenderer();
    private final TrapTriggerService trapTriggers = new TrapTriggerService(8.0, 5_000L);
    private QuickBuyRepository quickBuyRepository;
    private JoinMenu joinMenu;
    private List<String> startItems = List.of();

    private int countdownRemaining = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            saveResource("arena.yml", false);
        } catch (IllegalArgumentException ignored) {
            // No bundled sample arena; config-derived arena will be used.
        }
        this.config = PluginConfig.from(getConfig(), "pod-" + UUID.randomUUID());

        this.reporter = new HttpPodReporter(config.controllerBaseUrl());
        this.eventBus = new EventBus();
        this.eventBus.subscribe(new DomainEventBridge(LOG, reporter, config.jsonLogs()));
        this.gameManager = new GameManager();
        this.languageService = new LanguageService(catalog);
        this.startedAtMillis = System.currentTimeMillis();

        TemplateDescriptor template = new TemplateDescriptor(
                config.templateName(), config.templateVersion(), config.templateSource(), Optional.empty());

        bootPersistence(config.database());

        ArenaDefinition arena = loadArenaDefinition(config);
        this.shop = arena != null ? arena.shop() : defaultShop();
        this.startItems = arena != null ? arena.startItems() : List.of();
        this.upgradeCatalog = arena != null ? arena.upgrades() : defaultUpgradeCatalog;
        this.game = buildGame(config, template, arena);
        gameManager.register(game);

        this.joinMenu = new JoinMenu(gameManager, game);
        registerListeners();
        getCommand("bedwars").setExecutor(new BedwarsCommand(this));
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // TPS is measured per server tick (20/s). Ticking the meter from the 1-second
        // game loop reported ~1.0 TPS on a perfectly healthy server.
        getServer().getScheduler().runTaskTimer(this, tpsMeter::tick, 0L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::applyTeamEffects, 40L, 40L);
        getServer().getScheduler().runTaskTimer(this, this::sendHeartbeat, 200L,
                Math.max(20L, config.heartbeatSeconds() * 20L));
        getServer().getScheduler().runTaskTimer(this, this::refreshLeaderboards, 400L,
                Math.max(20L, config.leaderboardRefreshSeconds() * 20L));

        loadTemplate(template, config);
        reporter.reportReady(config.serverId(), config.arenaGroup(), template, game.id());
        LOG.info("pod_ready pod={} game={} template={} shop={}", config.serverId(), game.id(),
                template.coordinate(), shop.id());
    }

    // ---- wiring ----------------------------------------------------------

    private void registerListeners() {
        var pm = getServer().getPluginManager();
        pm.registerEvents(new GameListener(gameManager), this);
        pm.registerEvents(new ShopListener(), this);
        pm.registerEvents(new UpgradeListener(), this);
        pm.registerEvents(new VoidKillListener(gameManager), this);
        pm.registerEvents(new ProtectionListener(gameManager), this);
        pm.registerEvents(new SpectatorListener(gameManager, this), this);
        pm.registerEvents(new JoinSignListener(gameManager, game), this);
        pm.registerEvents(new NpcJoinListener(gameManager, game), this);
        pm.registerEvents(new TrapTriggerListener(gameManager, trapTriggers), this);
        pm.registerEvents(new QuickBuyListener(), this);
        pm.registerEvents(joinMenu, this);
        if (quickBuyRepository != null) {
            pm.registerEvents(new QuickBuySyncListener(quickBuyRepository, quickBuy), this);
        }
    }

    /** Periodically applies team upgrade effects to every participant's gear and buffs. */
    private void applyTeamEffects() {
        if (game.state() != GameState.RUNNING && game.state() != GameState.SUDDEN_DEATH) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            game.session(player.getUniqueId()).ifPresent(session ->
                    session.teamId().flatMap(game::team).ifPresent(team -> {
                        var effects = TeamEffectCalculator.forTeam(team.upgrades());
                        boolean inBase = new Vec3(player.getLocation().getX(), player.getLocation().getY(),
                                player.getLocation().getZ()).isWithin(team.bed().position(), 8.0);
                        UpgradeEffectApplier.apply(player, effects, inBase);
                    }));
        }
    }

    private void giveStartItems() {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().getFirst();
        if (world == null || startItems.isEmpty()) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (game.session(player.getUniqueId()).isEmpty()) {
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

    private void bootPersistence(DatabaseConfig databaseConfig) {
        try {
            this.database = new Database(databaseConfig, LOG);
            new SchemaMigrator(database, LOG).migrate(Migrations.all());
            this.statsRepository = new StatsRepository(database, LOG, 1000);
            this.leaderboardCache = new LeaderboardCache(database, LOG, 100);
            this.quickBuyRepository = new QuickBuyRepository(database, LOG);
            this.matchResultPersister = new MatchResultPersister(new EloCalculator(config.kFactor()));
        } catch (RuntimeException e) {
            LOG.error("Persistence unavailable; running without stats", e);
        }
    }

    /** Persists the finished match's stat deltas and ELO exactly once. */
    private void persistResults() {
        if (resultsPersisted || matchResultPersister == null || statsRepository == null || game == null) {
            return;
        }
        resultsPersisted = true;
        matchResultPersister.persist(game.results(), statsRepository)
                .thenRun(() -> LOG.info("match_results_persisted game={}", game.id()))
                .exceptionally(error -> {
                    LOG.error("Failed to persist match results for {}", game.id(), error);
                    return null;
                });
    }

    /** Reports TPS, player count and phase so the controller can scrape pod health. */
    private void sendHeartbeat() {
        if (game == null) {
            return;
        }
        PodHeartbeat beat = new PodHeartbeat(config.serverId(), game.id(), tpsMeter.tps(),
                game.playerCount(), game.phase(), System.currentTimeMillis() - startedAtMillis);
        reporter.heartbeat(beat);
        LOG.info("heartbeat pod={} game={} tps={} players={} phase={} uptime={}ms",
                beat.podId(), beat.gameId(), String.format("%.1f", beat.tps()), beat.playerCount(),
                beat.phase(), beat.uptimeMillis());
    }

    private void refreshLeaderboards() {
        if (leaderboardCache != null) {
            leaderboardCache.refresh();
        }
    }

    /**
     * Config hot-reload: re-reads {@code config.yml} and {@code arena.yml} and
     * applies the values that can change safely mid-match (shop, upgrade tree,
     * start items, countdown, sudden death). Arena geometry needs a pod restart —
     * which is the pod-per-match model anyway.
     */
    public void reloadConfiguration() {
        reloadConfig();
        PluginConfig fresh = PluginConfig.from(getConfig(), config.serverId());
        this.config = fresh;
        ArenaDefinition arena = loadArenaDefinition(fresh);
        this.shop = arena != null ? arena.shop() : defaultShop();
        this.startItems = arena != null ? arena.startItems() : List.of();
        this.upgradeCatalog = arena != null ? arena.upgrades() : defaultUpgradeCatalog;
        LOG.info("config_reloaded shop={} upgrades={} startItems={}",
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
                    LOG.error("template_load_failed template={}", template.coordinate(), error);
                    return null;
                });
    }

    /**
     * Hands the staged archive to the world loader. Production uses AdvancedSlimePaper
     * (the template is read read-only and cloned per match, so the pod never mutates
     * the shared template); without ASP the documented non-production fallback is used.
     */
    private void loadWorld(TemplateDescriptor template, java.nio.file.Path archive) {
        SlimeWorldProvider provider = worldProvider();
        try {
            boolean ok = provider.load(archive, template.name(), "match-" + game.id());
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

    /** Builds the ASP world loader when AdvancedSlimePaper is installed; null otherwise. */
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

    /**
     * The ASP API artifact exposes {@link SlimeLoader} but not the server's configured
     * loaders, so one reflective hop reaches the loader the server built for the given
     * data source (file/S3/MySQL). This is an integration seam, not bytecode patching.
     */
    private SlimeLoader resolveAspLoader(String dataSource) throws Exception {
        org.bukkit.plugin.Plugin asp = getServer().getPluginManager().getPlugin("AdvancedSlimePaper");
        if (asp == null) {
            return null;
        }
        Object loader = asp.getClass().getMethod("getLoader", String.class).invoke(asp, dataSource);
        return loader instanceof SlimeLoader slime ? slime : null;
    }

    /** Loads {@code arena.yml} if present (the template's bed/spawn/generator/shop layout). */
    private ArenaDefinition loadArenaDefinition(PluginConfig config) {
        Path arenaFile = getDataFolder().toPath().resolve("arena.yml");
        if (!Files.isRegularFile(arenaFile)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(arenaFile)) {
            return new ArenaConfigLoader().load(in);
        } catch (Exception e) {
            LOG.error("Failed to load arena.yml; falling back to config-derived arena", e);
            return null;
        }
    }

    private Game buildGame(PluginConfig config, TemplateDescriptor template, ArenaDefinition arena) {
        ArenaGroup group = arena != null ? arena.group() : new ArenaGroup(
                config.arenaGroup(), config.teamCount(), config.playersPerTeam(),
                config.countdownSeconds(), config.suddenDeathAfterSeconds(),
                config.voidY(), config.islandRadius(), config.bedProtectionRadius(),
                List.of(GeneratorType.IRON, GeneratorType.GOLD, GeneratorType.DIAMOND, GeneratorType.EMERALD));

        List<Team> teams = new ArrayList<>();
        List<Generator> generators = new ArrayList<>();
        long now = System.currentTimeMillis();

        if (arena != null) {
            for (Map.Entry<String, Vec3> entry : arena.teamBeds().entrySet()) {
                String id = entry.getKey();
                TeamColor color = colorFor(id, teams.size());
                teams.add(new Team(id, color, new Bed(id, entry.getValue(), group.bedProtectionRadius()),
                        group.playersPerTeam()));
            }
            for (var spec : arena.generators()) {
                generators.add(new Generator(spec.id(), spec.type(), spec.tier(), spec.position(), now));
            }
        } else {
            TeamColor[] colors = TeamColor.values();
            for (int i = 0; i < group.teamCount(); i++) {
                TeamColor color = colors[i % colors.length];
                String id = color.name().toLowerCase();
                Vec3 bedPos = new Vec3(i * 40.0, 64.0, 0.0);
                teams.add(new Team(id, color, new Bed(id, bedPos, group.bedProtectionRadius()), group.playersPerTeam()));
            }
        }

        for (Team team : teams) {
            boolean hasGenerator = generators.stream()
                    .anyMatch(g -> g.id().startsWith(team.id() + "-"));
            if (!hasGenerator) {
                generators.add(new Generator(team.id() + "-iron", GeneratorType.IRON, GeneratorTier.I,
                        team.bed().position(), now));
                generators.add(new Generator(team.id() + "-gold", GeneratorType.GOLD, GeneratorTier.I,
                        team.bed().position(), now));
            }
        }
        if (generators.stream().noneMatch(g -> g.type() == GeneratorType.DIAMOND)) {
            generators.add(new Generator("center-diamond", GeneratorType.DIAMOND, GeneratorTier.I, new Vec3(0, 64, 0), now));
        }
        if (generators.stream().noneMatch(g -> g.type() == GeneratorType.EMERALD)) {
            generators.add(new Generator("center-emerald", GeneratorType.EMERALD, GeneratorTier.I, new Vec3(0, 64, 0), now));
        }

        return new Game("game-" + UUID.randomUUID(), group, template, teams, generators, eventBus, 5, now);
    }

    private static TeamColor colorFor(String id, int index) {
        for (TeamColor color : TeamColor.values()) {
            if (color.name().equalsIgnoreCase(id)) {
                return color;
            }
        }
        return TeamColor.values()[index % TeamColor.values().length];
    }

    // ---- tick loop -------------------------------------------------------

    private void tick() {
        // Correlate everything this tick does (logs, persistence, webhooks) with the
        // match and the pod via scoped values; the binding is restored automatically.
        CorrelationContext.run(game.id(), config.serverId(), this::tickGame);
    }

    private void tickGame() {
        if (game.state().isTerminal()) {
            persistResults();
            return;
        }
        long now = System.currentTimeMillis();
        spawnGeneratorItems(now);
        handleCountdown();
        handleSuddenDeath(now);
        updateScoreboards();
    }

    private void spawnGeneratorItems(long now) {
        World world = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().getFirst();
        if (world == null) {
            return;
        }
        for (Game.GeneratorSpawn spawn : game.tickGenerators(now)) {
            Material material = materialFor(spawn.type());
            if (material != null) {
                world.dropItemNaturally(new org.bukkit.Location(world,
                        spawn.position().x(), spawn.position().y(), spawn.position().z()),
                        new ItemStack(material, spawn.itemCount()));
            }
        }
    }

    private void handleCountdown() {
        if (game.state() == GameState.WAITING) {
            if (game.playerCount() >= game.group().teamCount()) {
                game.startCountdown();
                countdownRemaining = game.group().countdownSeconds();
            }
            return;
        }
        if (game.state() == GameState.COUNTDOWN) {
            countdownRemaining--;
            if (countdownRemaining <= 0) {
                game.beginMatch(System.currentTimeMillis());
                giveStartItems();
            } else if (countdownRemaining <= 5) {
                broadcast("&eStarting in &c" + countdownRemaining + "&e...");
            }
        }
    }

    private void handleSuddenDeath(long now) {
        int after = game.group().suddenDeathAfterSecs();
        if (game.state() == GameState.RUNNING && after > 0 && game.startedAtMillis() > 0
                && now - game.startedAtMillis() >= after * 1000L) {
            game.enterSuddenDeath(now);
            broadcast("&4Sudden death! All beds have been destroyed.");
        }
    }

    private void updateScoreboards() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (gameManager.byPlayer(player.getUniqueId()).isPresent()) {
                scoreboardRenderer.update(player, game);
            }
        }
    }

    private void broadcast(String legacyMessage) {
        String coloured = org.bukkit.ChatColor.translateAlternateColorCodes('&', legacyMessage);
        for (Player player : getServer().getOnlinePlayers()) {
            player.sendMessage(coloured);
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

    public Game game() {
        return game;
    }

    public GameManager gameManager() {
        return gameManager;
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
        if (game != null) {
            persistResults();
            reporter.reportDraining(config.serverId(), game.id(), game.activePlayerCount());
            if (game.state() == GameState.RUNNING) {
                game.endGame(game.winnerTeamId(), System.currentTimeMillis());
            }
            reporter.reportGameEnded(config.serverId(), game.results());
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
        LOG.info("pod_shutdown complete");
    }
}