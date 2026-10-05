package dev.bedwars.core.domain;

import dev.bedwars.api.dto.BedBreak;
import dev.bedwars.api.dto.GamePhase;
import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.PlayerStatDelta;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.event.GameEvent;
import dev.bedwars.core.event.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns <em>everything</em> that must not leak between matches: teams, beds,
 * generators, per-player sessions, kill attribution. All mutable state for one
 * match lives here, and only here.
 *
 * <p>Time is injected everywhere ({@code nowMillis}) so the aggregate is
 * deterministic and testable without a running server. The class is not
 * thread-safe; a match is driven by a single logical owner.
 */
public final class Game {

    private final String id;
    private final ArenaGroup group;
    private final TemplateDescriptor template;
    private final EventBus bus;
    private final int respawnDelaySeconds;

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, PlayerSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, String> playerTeam = new HashMap<>();
    private final List<Generator> generators = new ArrayList<>();
    private final List<BedBreak> bedBreaks = new ArrayList<>();
    private final KillTracker killTracker = new KillTracker(8_000L);

    private final long createdAtMillis;
    private GameState state = GameState.WAITING;
    private long startedAtMillis = -1L;
    private long endedAtMillis = -1L;
    private String winnerTeamId;

    public Game(String id,
                ArenaGroup group,
                TemplateDescriptor template,
                List<Team> teams,
                List<Generator> generators,
                EventBus bus,
                int respawnDelaySeconds,
                long createdAtMillis) {
        this.id = Objects.requireNonNull(id, "id");
        this.group = Objects.requireNonNull(group, "group");
        this.template = Objects.requireNonNull(template, "template");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.respawnDelaySeconds = respawnDelaySeconds;
        this.createdAtMillis = createdAtMillis;
        for (Team team : teams) {
            this.teams.put(team.id(), team);
        }
        this.generators.addAll(generators);
    }

    // ---- accessors -------------------------------------------------------

    public String id() {
        return id;
    }

    public ArenaGroup group() {
        return group;
    }

    public TemplateDescriptor template() {
        return template;
    }

    public GameState state() {
        return state;
    }

    public GamePhase phase() {
        return switch (state) {
            case WAITING -> GamePhase.WAITING;
            case COUNTDOWN -> GamePhase.COUNTDOWN;
            case RUNNING -> GamePhase.RUNNING;
            case SUDDEN_DEATH -> GamePhase.SUDDEN_DEATH;
            case ENDED, ABORTED -> GamePhase.ENDED;
        };
    }

    public List<Team> teams() {
        return List.copyOf(teams.values());
    }

    public Optional<Team> team(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    public List<Generator> generators() {
        return List.copyOf(generators);
    }

    public int playerCount() {
        return sessions.size();
    }

    public int activePlayerCount() {
        return (int) sessions.values().stream().filter(PlayerSession::isActive).count();
    }

    public Optional<PlayerSession> session(UUID uuid) {
        return Optional.ofNullable(sessions.get(uuid));
    }

    /** Live view of every session in this match, keyed by player UUID. */
    public Map<UUID, PlayerSession> sessions() {
        return sessions;
    }

    public Optional<String> teamOf(UUID uuid) {
        return Optional.ofNullable(playerTeam.get(uuid));
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public long startedAtMillis() {
        return startedAtMillis;
    }

    public Optional<String> winnerTeamId() {
        return Optional.ofNullable(winnerTeamId);
    }

    // ---- lifecycle -------------------------------------------------------

    private void transition(GameState next) {
        GameState previous = state;
        if (!previous.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal transition " + previous + " -> " + next + " in game " + id);
        }
        state = next;
        bus.publish(new GameEvent.PhaseChanged(id, phaseOf(previous), phaseOf(next)));
    }

    private static GamePhase phaseOf(GameState s) {
        return switch (s) {
            case WAITING -> GamePhase.WAITING;
            case COUNTDOWN -> GamePhase.COUNTDOWN;
            case RUNNING -> GamePhase.RUNNING;
            case SUDDEN_DEATH -> GamePhase.SUDDEN_DEATH;
            case ENDED, ABORTED -> GamePhase.ENDED;
        };
    }

    public void startCountdown() {
        transition(GameState.COUNTDOWN);
    }

    public void beginMatch(long nowMillis) {
        transition(GameState.RUNNING);
        this.startedAtMillis = nowMillis;
        // Any team that never received a player is out from the first tick; otherwise
        // an empty team would count as "still standing" forever and the match could
        // never reach a single survivor.
        evaluateEliminations(nowMillis);
        bus.publish(new GameEvent.GameStarted(id, group.id(), template, sessions.size()));
        checkWinCondition(nowMillis);
    }

    /** Sudden death: every bed is destroyed and generators are maxed. */
    public void enterSuddenDeath(long nowMillis) {
        transition(GameState.SUDDEN_DEATH);
        for (Team team : teams.values()) {
            if (team.bed().forceDestroy()) {
                bedBreaks.add(new BedBreak(team.id(), null, nowMillis - startedAtMillis));
            }
        }
        for (Generator generator : generators) {
            generator.setTier(GeneratorTier.MAX, nowMillis);
        }
        evaluateEliminations(nowMillis);
    }

    public void abort() {
        if (!state.isTerminal()) {
            transition(GameState.ABORTED);
        }
    }

    public void endGame(Optional<String> winner, long nowMillis) {
        if (state.isTerminal()) {
            return;
        }
        transition(GameState.ENDED);
        this.endedAtMillis = nowMillis;
        this.winnerTeamId = winner.orElse(null);
        bus.publish(new GameEvent.GameEnded(id, winner, endedAtMillis - startedAtMillis));
    }

    // ---- players ---------------------------------------------------------

    /** Adds a player and auto-balances them onto the smallest available team. */
    public PlayerSession addPlayer(UUID uuid, String username) {
        PlayerSession existing = sessions.get(uuid);
        if (existing != null) {
            return existing;
        }
        if (state != GameState.WAITING && state != GameState.COUNTDOWN) {
            throw new IllegalStateException("Cannot join game " + id + " in state " + state);
        }
        Team team = teams.values().stream()
                .filter(t -> !t.isEliminated() && !t.isFull())
                .min((a, b) -> Integer.compare(a.size(), b.size()))
                .orElseThrow(() -> new IllegalStateException("No free team in game " + id));
        PlayerSession session = new PlayerSession(uuid, username);
        session.assignTeam(team.id());
        sessions.put(uuid, session);
        playerTeam.put(uuid, team.id());
        team.addMember(uuid);
        return session;
    }

    public void removePlayer(UUID uuid, long nowMillis) {
        PlayerSession session = sessions.remove(uuid);
        String teamId = playerTeam.remove(uuid);
        if (teamId != null) {
            teams.get(teamId).removeMember(uuid);
        }
        if (session != null && !state.isTerminal()) {
            evaluateEliminations(nowMillis);
            checkWinCondition(nowMillis);
        }
    }

    // ---- combat ----------------------------------------------------------

    public void recordDamage(UUID victim, UUID attacker, long nowMillis) {
        killTracker.recordDamage(victim, attacker, nowMillis);
    }

    /**
     * Resolves a death: credits the killer, updates counters, and applies the
     * bed rule (final death only when the team's bed is gone).
     */
    public void onDeath(UUID victim, long nowMillis) {
        PlayerSession victimSession = sessions.get(victim);
        if (victimSession == null) {
            return;
        }
        Optional<UUID> killer = killTracker.killerOf(victim, nowMillis);
        String teamId = playerTeam.get(victim);
        boolean bedGone = teamId != null && teams.get(teamId).bed().isDestroyed();

        killer.flatMap(k -> Optional.ofNullable(sessions.get(k)))
                .ifPresent(killerSession -> killerSession.addKill(bedGone));

        victimSession.addDeath(bedGone);
        victimSession.setState(bedGone ? PlayerState.ELIMINATED : PlayerState.RESPAWNING);
        if (!bedGone) {
            victimSession.scheduleRespawn(nowMillis + respawnDelaySeconds * 1000L);
        }
        killTracker.onDeath(victim);
        killer.ifPresent(killTracker::onKill);

        bus.publish(new GameEvent.PlayerEliminated(id, victim, killer, bedGone));

        evaluateEliminations(nowMillis);
        checkWinCondition(nowMillis);
    }

    public void onRespawn(UUID uuid) {
        PlayerSession session = sessions.get(uuid);
        if (session != null && session.state() == PlayerState.RESPAWNING) {
            session.setState(PlayerState.ALIVE);
            bus.publish(new GameEvent.PlayerRespawned(id, uuid));
        }
    }

    public boolean destroyBed(String teamId, UUID breaker, long nowMillis) {
        Team team = teams.get(teamId);
        if (team == null || !team.bed().destroy(breaker, nowMillis)) {
            return false;
        }
        PlayerSession breakerSession = sessions.get(breaker);
        if (breakerSession != null) {
            breakerSession.addBedBroken();
        }
        team.members().forEach(m -> {
            PlayerSession s = sessions.get(m);
            if (s != null) {
                s.addBedLost();
            }
        });
        bedBreaks.add(new BedBreak(teamId, breaker, nowMillis - startedAtMillis));
        bus.publish(new GameEvent.BedDestroyed(id, teamId, breaker));
        evaluateEliminations(nowMillis);
        checkWinCondition(nowMillis);
        return true;
    }

    /**
     * Marks teams out. A team is finished when either:
     * <ul>
     *   <li>it has <em>no members at all</em> (nobody ever joined, or the last player
     *       quit) &mdash; it can never win, so it must not prop up the win condition; or</li>
     *   <li>its bed is destroyed and no member can still respawn (all eliminated or
     *       spectating).</li>
     * </ul>
     * The previous implementation only applied the second rule, so an unfilled or
     * abandoned team kept the match alive indefinitely.
     */
    private void evaluateEliminations(long nowMillis) {
        for (Team team : teams.values()) {
            if (team.isEliminated()) {
                continue;
            }
            boolean abandoned = team.size() == 0;
            boolean noActiveMembers = team.members().stream()
                    .map(sessions::get)
                    .filter(Objects::nonNull)
                    .noneMatch(PlayerSession::isActive);
            if (abandoned || (team.bed().isDestroyed() && noActiveMembers)) {
                team.eliminate();
                // Guard against a member whose session was already removed: an NPE here
                // would abort the whole death/quit path and leave the match unstoppable.
                team.members().forEach(m -> {
                    PlayerSession session = sessions.get(m);
                    if (session != null) {
                        session.setState(PlayerState.SPECTATOR);
                    }
                });
                bus.publish(new GameEvent.TeamEliminated(id, team.id()));
            }
        }
    }

    private void checkWinCondition(long nowMillis) {
        if (state != GameState.RUNNING && state != GameState.SUDDEN_DEATH) {
            return;
        }
        List<Team> standing = teams.values().stream().filter(t -> !t.isEliminated()).toList();
        if (standing.size() <= 1) {
            endGame(standing.isEmpty() ? Optional.empty() : Optional.of(standing.getFirst().id()), nowMillis);
        }
    }

    // ---- generators ------------------------------------------------------

    /** Advances all generators; returns the spawns the caller must realise in-world. */
    public List<GeneratorSpawn> tickGenerators(long nowMillis) {
        List<GeneratorSpawn> spawns = new ArrayList<>();
        for (Generator generator : generators) {
            int cycles = generator.tick(nowMillis);
            if (cycles > 0) {
                spawns.add(new GeneratorSpawn(generator.id(), generator.type(),
                        generator.position(), cycles * generator.itemsPerSpawn()));
            }
        }
        return spawns;
    }

    public record GeneratorSpawn(String generatorId, GeneratorType type, Vec3 position, int itemCount) {
    }

    // ---- world rules -----------------------------------------------------

    /** A player below the void threshold is dead. */
    public boolean isVoidKill(Vec3 position) {
        return position.y() < group.voidYThreshold();
    }

    /** True if {@code point} is within a team's island radius (build anchor = bed). */
    public boolean isWithinIsland(Vec3 point, String teamId) {
        return team(teamId).map(team -> point.isWithin(team.bed().position(), group.islandRadius())).orElse(false);
    }

    /** True if block placement at {@code point} is forbidden by a standing bed's protection radius. */
    public boolean isProtectedFromBuild(Vec3 point) {
        return teams.values().stream()
                .filter(team -> !team.bed().isDestroyed())
                .anyMatch(team -> point.isWithin(team.bed().position(), team.bed().protectionRadius()));
    }

    /** Turns a player into a spectator (after elimination). */
    public void makeSpectator(UUID uuid) {
        session(uuid).ifPresent(session -> session.setState(PlayerState.SPECTATOR));
    }

    /** Applies a team's Iron Forge level to its iron and gold generators. */
    public void applyForge(String teamId, long nowMillis) {
        int level = team(teamId).map(team -> team.upgrades().levelOf(
                dev.bedwars.core.upgrade.UpgradeType.IRON_FORGE)).orElse(0);
        GeneratorTier tier = switch (level) {
            case 0 -> GeneratorTier.I;
            case 1 -> GeneratorTier.II;
            case 2 -> GeneratorTier.III;
            case 3 -> GeneratorTier.IV;
            default -> GeneratorTier.MAX;
        };
        for (Generator generator : generators) {
            boolean teamGenerator = generator.id().startsWith(teamId + "-");
            boolean forgeable = generator.type() == GeneratorType.IRON || generator.type() == GeneratorType.GOLD;
            if (teamGenerator && forgeable) {
                generator.setTier(tier, nowMillis);
            }
        }
    }

    // ---- results ---------------------------------------------------------

    public GameResult results() {
        List<PlayerStatDelta> deltas = new ArrayList<>();
        for (PlayerSession s : sessions.values()) {
            boolean winner = winnerTeamId != null && winnerTeamId.equals(s.teamId().orElse(null));
            deltas.add(new PlayerStatDelta(s.uuid(), s.username(), s.kills(), s.finalKills(), s.deaths(), s.finalDeaths(),
                    s.bedsBroken(), s.bedsLost(), winner, s.experienceGained(winner)));
        }
        return new GameResult(id, group.id(), template, startedAtMillis,
                endedAtMillis < 0 ? startedAtMillis : endedAtMillis,
                Optional.ofNullable(winnerTeamId), deltas, bedBreaks);
    }
}