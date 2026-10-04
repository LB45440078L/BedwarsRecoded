package dev.bedwars.api.service;

import dev.bedwars.api.dto.GamePhase;

/** Metrics a pod periodically reports to the controller. */
public record PodHeartbeat(
        String podId,
        String gameId,
        double tps,
        int playerCount,
        GamePhase phase,
        long uptimeMillis
) {
}