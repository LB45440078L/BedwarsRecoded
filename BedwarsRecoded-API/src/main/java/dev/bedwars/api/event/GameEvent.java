package dev.bedwars.api.event;

import dev.bedwars.api.dto.GamePhase;
import dev.bedwars.api.dto.TemplateDescriptor;

import java.util.Optional;
import java.util.UUID;

/**
 * Platform-neutral game events. Core raises these; adapters (Spigot/Velocity)
 * translate them into their own event systems. Keeping them here means the
 * domain never imports Bukkit, so {@code Core} stays unit-testable.
 *
 * <p>Sealed so the compiler can enforce exhaustive handling in dispatchers.
 */
public sealed interface GameEvent {

    String gameId();

    record GameStarted(String gameId, String arenaGroup, TemplateDescriptor template, int playerCount)
            implements GameEvent {
    }

    record PhaseChanged(String gameId, GamePhase from, GamePhase to) implements GameEvent {
    }

    record BedDestroyed(String gameId, String teamId, UUID breaker) implements GameEvent {
    }

    record TeamEliminated(String gameId, String teamId) implements GameEvent {
    }

    record PlayerEliminated(String gameId, UUID victim, Optional<UUID> killer, boolean finale) implements GameEvent {
    }

    record PlayerRespawned(String gameId, UUID player) implements GameEvent {
    }

    record GameEnded(String gameId, Optional<String> winnerTeamId, long durationMillis) implements GameEvent {
    }
}