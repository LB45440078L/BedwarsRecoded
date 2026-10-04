package dev.bedwars.core.event;

import dev.bedwars.api.event.GameEvent;
import dev.bedwars.api.event.GameEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal synchronous event bus for domain events. Adapters register a listener
 * that forwards into their platform's event system. Kept deliberately small —
 * Core must not depend on any specific bus implementation.
 */
public final class EventBus {

    private final List<GameEventListener> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(GameEventListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(GameEventListener listener) {
        listeners.remove(listener);
    }

    public void publish(GameEvent event) {
        for (GameEventListener listener : listeners) {
            listener.onGameEvent(event);
        }
    }
}