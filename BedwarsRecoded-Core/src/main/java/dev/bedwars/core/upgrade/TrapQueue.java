package dev.bedwars.core.upgrade;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A team's armed traps, in trigger order (FIFO). A team may hold at most three.
 */
public final class TrapQueue {

    public static final int MAX_TRAPS = 3;

    private final Deque<TrapType> armed = new ArrayDeque<>();

    public boolean add(TrapType trap) {
        if (armed.size() >= MAX_TRAPS) {
            return false;
        }
        armed.addLast(trap);
        return true;
    }

    public int size() {
        return armed.size();
    }

    public boolean isFull() {
        return armed.size() >= MAX_TRAPS;
    }

    public List<TrapType> snapshot() {
        return List.copyOf(armed);
    }

    /** Removes and returns the next trap to trigger, if any. */
    public Optional<TrapType> triggerNext() {
        return Optional.ofNullable(armed.pollFirst());
    }

    public void clear() {
        armed.clear();
    }
}