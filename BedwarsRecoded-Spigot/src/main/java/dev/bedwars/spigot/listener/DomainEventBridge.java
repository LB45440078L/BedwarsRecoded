package dev.bedwars.spigot.listener;

import dev.bedwars.api.event.GameEvent;
import dev.bedwars.api.event.GameEventListener;
import dev.bedwars.api.service.PodReporter;
import dev.bedwars.core.logging.CorrelationContext;
import dev.bedwars.core.logging.StructuredLog;
import org.slf4j.Logger;

import java.util.Map;

/**
 * Bridges domain events to infrastructure: logging plus the controller webhook.
 * Registered on the Core {@code EventBus}.
 *
 * <p>When {@code logging.json} is enabled each event is also emitted as a single
 * JSON line carrying {@code game_id}/{@code player_uuid} correlation fields, so
 * Loki/ELK can index them without parsing free text.
 */
public final class DomainEventBridge implements GameEventListener {

    private final Logger log;
    private final PodReporter reporter;
    private final boolean json;

    public DomainEventBridge(Logger log, PodReporter reporter, boolean json) {
        this.log = log;
        this.reporter = reporter;
        this.json = json;
    }

    @Override
    public void onGameEvent(GameEvent event) {
        switch (event) {
            case GameEvent.GameStarted started -> {
                log.info("game_started game={} group={} players={} template={}",
                        started.gameId(), started.arenaGroup(), started.playerCount(), started.template().coordinate());
                emit("game_started", Map.of(
                        "game_id", started.gameId(),
                        "arena_group", started.arenaGroup(),
                        "player_count", started.playerCount(),
                        "template", started.template().coordinate()));
                reporter.reportGameStarted(started.gameId(), started.playerCount());
            }
            case GameEvent.BedDestroyed bed -> {
                log.info("bed_destroyed game={} team={} by={}", bed.gameId(), bed.teamId(), bed.breaker());
                emit("bed_destroyed", Map.of(
                        "game_id", bed.gameId(), "team_id", bed.teamId(), "player_uuid", bed.breaker()));
            }
            case GameEvent.TeamEliminated eliminated -> {
                log.info("team_eliminated game={} team={}", eliminated.gameId(), eliminated.teamId());
                emit("team_eliminated", Map.of("game_id", eliminated.gameId(), "team_id", eliminated.teamId()));
            }
            case GameEvent.PlayerEliminated death -> {
                log.info("player_eliminated game={} victim={} killer={} final={}",
                        death.gameId(), death.victim(), death.killer().orElse(null), death.finale());
                emit("player_eliminated", Map.of(
                        "game_id", death.gameId(),
                        "player_uuid", death.victim(),
                        "killer_uuid", death.killer().orElse(null),
                        "final", death.finale()));
            }
            case GameEvent.PlayerRespawned respawn -> log.debug("player_respawned game={} player={}",
                    respawn.gameId(), respawn.player());
            case GameEvent.PhaseChanged phase -> {
                log.info("phase_changed game={} {}->{}", phase.gameId(), phase.from(), phase.to());
                emit("phase_changed", Map.of(
                        "game_id", phase.gameId(), "from", phase.from().name(), "to", phase.to().name()));
            }
            case GameEvent.GameEnded ended -> {
                log.info("game_ended game={} winner={} durationMs={}",
                        ended.gameId(), ended.winnerTeamId().orElse("draw"), ended.durationMillis());
                emit("game_ended", Map.of(
                        "game_id", ended.gameId(),
                        "winner_team_id", ended.winnerTeamId().orElse(null),
                        "duration_ms", ended.durationMillis()));
            }
        }
    }

    private void emit(String event, Map<String, Object> fields) {
        if (json) {
            // Merge the request-scoped correlation ids so every line carries
            // game_id/pod_id even when the caller did not pass them.
            java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(fields);
            CorrelationContext.fields().forEach(merged::putIfAbsent);
            log.info(StructuredLog.json(event, merged));
        }
    }
}