package dev.bedwars.api.dto;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A group of players that must be placed into the same match atomically.
 * Party cohesion is enforced by the controller: it never splits a party across
 * pods (see the queue requirements).
 */
public record PartyInfo(String partyId, UUID leader, List<UUID> members) {
    public PartyInfo {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(leader, "leader");
        members = List.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }
}