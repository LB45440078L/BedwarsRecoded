package dev.bedwars.api.service;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a queue request.
 *
 * @param podAddress  the game pod the proxy should route to (host:port)
 * @param members     every UUID to route (a whole party, never split)
 * @param gameId      the match the players are joining
 * @param retryAfterMillis when empty routing failed and the client should retry after this delay
 */
public record DispatchResult(
        String podAddress,
        String gameId,
        List<UUID> members,
        long retryAfterMillis
) {
    public static DispatchResult retry(long millis) {
        return new DispatchResult(null, null, List.of(), millis);
    }

    public boolean successful() {
        return podAddress != null;
    }
}