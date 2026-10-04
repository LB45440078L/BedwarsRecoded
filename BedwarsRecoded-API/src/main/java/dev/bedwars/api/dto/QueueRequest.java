package dev.bedwars.api.dto;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A player (or party leader) asking the controller for a game slot.
 *
 * @param priority        higher wins; used to give e.g. pre-warmed slots to ranked queues
 * @param preferredGroup  optional arena-group preference (solo/doubles/4s); empty means "any"
 * @param party           optional party; when present the whole party is dispatched together
 */
public record QueueRequest(
        UUID player,
        String username,
        int priority,
        Optional<String> preferredGroup,
        Optional<PartyInfo> party,
        long requestedAtMillis
) {
    public QueueRequest {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(username, "username");
        if (preferredGroup == null) {
            preferredGroup = Optional.empty();
        }
        if (party == null) {
            party = Optional.empty();
        }
    }
}