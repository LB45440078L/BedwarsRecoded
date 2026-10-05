package dev.bedwars.controller.pod;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks every game server and its free <em>match slots</em>.
 *
 * <p>A server hosts up to {@code capacity} concurrent matches; {@link #allocate}
 * reserves one slot atomically and returns the server, so two simultaneous lobby
 * requests can never receive the same slot. Servers report their used/free counts as
 * matches start and end, so capacity stays accurate without the controller guessing.
 *
 * <p>This replaces the old "a pod is one match" registry: capacity is now measured in
 * matches, which is what makes many matches per server work.
 */
public final class ServerRegistry {

    private static final class Entry {
        private final String group;
        private final int capacity;
        private final AtomicInteger free;

        private Entry(String group, int capacity, int free) {
            this.group = group;
            this.capacity = capacity;
            this.free = new AtomicInteger(free);
        }
    }

    private final Map<String, Entry> servers = new ConcurrentHashMap<>();

    /** Registers (or re-registers) a server. Idempotent by id; free slots reset to capacity. */
    public synchronized void register(String serverId, String group, int capacity) {
        int capped = Math.max(1, capacity);
        servers.put(serverId, new Entry(group == null || group.isBlank() ? "any" : group, capped, capped));
    }

    /** Authoritative capacity report from a running server. */
    public synchronized void updateFreeSlots(String serverId, int freeSlots) {
        Entry entry = servers.get(serverId);
        if (entry != null) {
            entry.free.set(Math.max(0, Math.min(entry.capacity, freeSlots)));
        }
    }

    /** Reserves one match slot on the busiest server with room (packing keeps servers full). */
    public synchronized Optional<String> allocate(String group) {
        return servers.entrySet().stream()
                .filter(entry -> entry.getValue().group.equals(group) && entry.getValue().free.get() > 0)
                .min(Comparator.comparingInt(entry -> entry.getValue().free.get()))
                .map(entry -> {
                    entry.getValue().free.decrementAndGet();
                    return entry.getKey();
                });
    }

    /** A match ended on the server: give the slot back. */
    public synchronized void releaseSlot(String serverId) {
        Entry entry = servers.get(serverId);
        if (entry != null) {
            entry.free.updateAndGet(free -> Math.min(entry.capacity, free + 1));
        }
    }

    /** The server left the pool (draining, destroyed, or failed). */
    public synchronized void markGone(String serverId) {
        servers.remove(serverId);
    }

    public synchronized int freeSlots(String group) {
        return servers.values().stream()
                .filter(entry -> entry.group.equals(group))
                .mapToInt(entry -> entry.free.get())
                .sum();
    }

    /** Free match slots per arena group. */
    public synchronized Map<String, Integer> freeSlotsByGroup() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : servers.values()) {
            counts.merge(entry.group, entry.free.get(), Integer::sum);
        }
        return counts;
    }

    public synchronized int totalFreeSlots() {
        return servers.values().stream().mapToInt(entry -> entry.free.get()).sum();
    }

    public synchronized int serverCount() {
        return servers.size();
    }

    /** Servers with no match running or starting — the only ones safe to reclaim. */
    public synchronized int idleServers() {
        return (int) servers.values().stream().filter(entry -> entry.free.get() >= entry.capacity).count();
    }

    public synchronized Set<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        servers.values().forEach(entry -> groups.add(entry.group));
        return groups;
    }
}
