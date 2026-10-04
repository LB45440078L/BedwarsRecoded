package dev.bedwars.api.service;

import dev.bedwars.api.dto.PartyInfo;
import dev.bedwars.api.dto.QueueRequest;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Lobby-side queueing. The lobby asks the controller for a slot; the controller
 * decides which (already-READY) pod to send the party to.
 */
public interface QueueService {

    /** Enqueue a solo player or party leader. Completes with a routing decision. */
    CompletableFuture<DispatchResult> enqueue(QueueRequest request);

    /** Remove a player (and, if leader, their party) from the queue. */
    CompletableFuture<Void> dequeue(java.util.UUID player);

    /** Current depth, per arena group, for lobby NPC displays. */
    CompletableFuture<java.util.Map<String, Integer>> queueDepthByGroup();
}