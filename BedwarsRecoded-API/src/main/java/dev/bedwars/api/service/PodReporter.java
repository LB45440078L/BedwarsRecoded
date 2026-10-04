package dev.bedwars.api.service;

import dev.bedwars.api.dto.GameResult;
import dev.bedwars.api.dto.TemplateDescriptor;

/**
 * Pod &rarr; controller reporting. Implementations POST to the controller
 * webhook and/or patch K8s annotations. All methods are non-blocking.
 */
public interface PodReporter {

    /** Announce that this pod has loaded its template and is accepting players. */
    void reportReady(String podId, String arenaGroup, TemplateDescriptor template, String gameId);

    /** Announce that a match has begun. */
    void reportGameStarted(String gameId, int playerCount);

    /** Flush the structured end-of-game payload for a pod leaving the pool. */
    void reportGameEnded(String podId, GameResult result);

    /** Periodic liveness + metrics heartbeat (TPS, player count, phase). */
    void heartbeat(PodHeartbeat heartbeat);

    /** Best-effort final report during SIGTERM drain; removes the pod from the pool. */
    void reportDraining(String podId, String gameId, int remainingPlayers);
}