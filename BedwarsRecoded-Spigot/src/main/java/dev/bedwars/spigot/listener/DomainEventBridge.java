package dev.bedwars.spigot.listener;

import dev.bedwars.api.event.GameEvent;
import dev.bedwars.api.event.GameEventListener;
import dev.bedwars.api.service.PodReporter;
import org.slf4j.Logger;

/**
 * Bridges domain events to infrastructure: structured logging plus the
 * controller webhook. Registered on the Core {@code EventBus}.
 */
public final class DomainEventBridge implements GameEventListener {

    private final Logger log;
    private final PodReporter reporter;

    public DomainEventBridge(Logger log, PodReporter reporter) {
        this.log = log;
        this.reporter = reporter;
    }

    @Override
    public void onGameEvent(GameEvent event) {
        switch (event) {
            case GameEvent.GameStarted started -> {
                log.info("game_started game={} group={} players={} template={}",
                        started.gameId(), started.arenaGroup(), started.playerCount(), started.template().coordinate());
                reporter.reportGameStarted(started.gameId(), started.playerCount());
            }
            case GameEvent.BedDestroyed bed -> log.info("bed_destroyed game={} team={} by={}",
                    bed.gameId(), bed.teamId(), bed.breaker());
            case GameEvent.TeamEliminated eliminated -> log.info("team_eliminated game={} team={}",
                    eliminated.gameId(), eliminated.teamId());
            case GameEvent.PlayerEliminated death -> log.info("player_eliminated game={} victim={} killer={} final={}",
                    death.gameId(), death.victim(), death.killer().orElse(null), death.finale());
            case GameEvent.PlayerRespawned respawn -> log.debug("player_respawned game={} player={}",
                    respawn.gameId(), respawn.player());
            case GameEvent.PhaseChanged phase -> log.info("phase_changed game={} {}->{}",
                    phase.gameId(), phase.from(), phase.to());
            case GameEvent.GameEnded ended -> log.info("game_ended game={} winner={} durationMs={}",
                    ended.gameId(), ended.winnerTeamId().orElse("draw"), ended.durationMillis());
        }
    }
}