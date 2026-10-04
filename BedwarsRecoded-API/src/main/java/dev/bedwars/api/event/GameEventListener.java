package dev.bedwars.api.event;

/** Receives domain events. Adapters implement this to bridge into their own bus. */
@FunctionalInterface
public interface GameEventListener {
    void onGameEvent(GameEvent event);
}