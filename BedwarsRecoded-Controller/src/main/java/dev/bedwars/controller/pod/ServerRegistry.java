package dev.bedwars.controller.pod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks every game server and its free <em>match slots</em>.
 *
 * <p>A server hosts up to {@code capacity} concurrent matches. Servers report their own
 * free match slots, and that report is the truth: capacity is counted in <em>matches</em>,
 * so placing a player never consumes one (players pack into the same match). {@link
 * #allocate} uses the reports to keep packing the fullest server, which is what makes
 * "everyone who queued together ends up in one match" true.
 *
 * <p>This replaces the old "a pod is one match" registry: capacity is now measured in
 * matches, which is what makes many matches per server work.
 */
public final class ServerRegistry {

    private static final class Entry {
        private final String group;
        private final int capacity;
        /** Free <em>match</em> slots, as the server itself last reported them. */
        private final AtomicInteger free;
        /**
         * Players placed here since that report.
         *
         * <p>Used <em>only</em> to break ties towards the server a burst is already filling.
         * It must never reduce {@link #free}: a server's capacity is measured in matches, and
         * one player joining does not consume a match -- sending players away from a server
         * that still has room splits them across the fleet, one or two per match, and then no
         * match ever reaches its minimum and no countdown ever starts.
         */
        private final AtomicInteger placedSinceReport = new AtomicInteger();

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
            // The report already accounts for everyone this server accepted, so the packing
            // hint starts again from what the server says.
            entry.placedSinceReport.set(0);
        }
    }

    /**
     * Picks the server a player should be sent to: the one packing the fullest, so people who
     * queue together end up in the same match.
     *
     * <p>Eligibility comes from the server's own free match slots; the packing hint only
     * orders the candidates. If the server turns out to have no room after all, it refuses the
     * player (who simply retries) -- a wasted retry is a far better failure than a fleet of
     * half-empty matches that never start.
     */
    public synchronized Optional<String> allocate(String group) {
        return servers.entrySet().stream()
                .filter(entry -> matchesGroup(entry.getValue().group, group) && entry.getValue().free.get() > 0)
                .min(Comparator
                        .comparingInt((Map.Entry<String, Entry> entry) -> packingOrder(entry.getValue()))
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    entry.getValue().placedSinceReport.incrementAndGet();
                    return entry.getKey();
                });
    }

    /** Lower sorts first: a server with fewer spare slots (and more recent placements) packs tighter. */
    private static int packingOrder(Entry entry) {
        return Math.max(0, entry.free.get() - entry.placedSinceReport.get());
    }

    /**
     * Does a server hosting {@code serverGroup} satisfy a request for {@code requested}?
     *
     * <p>A request that names no group -- null, blank, or the "any" placeholder -- matches
     * every server: the player wants a match and a server is a match. Requiring the literal
     * string to be equal is a deadlock in production, not a strictness win: servers register
     * under the arena group they actually host (solo/doubles/…), so a queue request carrying
     * "any" matches nothing, forever, and the whole lobby waits while capacity sits idle.
     */
    private static boolean matchesGroup(String serverGroup, String requested) {
        if (requested == null) {
            return true;
        }
        String wanted = requested.trim();
        if (wanted.isEmpty() || "any".equalsIgnoreCase(wanted)) {
            return true;
        }
        return serverGroup.equalsIgnoreCase(wanted);
    }

    /** A read-only view of one server, for operator tooling. */
    public record Snapshot(String serverId, String group, int capacity, int freeSlots, boolean idle) {
    }

    /** Every registered server, by id. Cheap: this is what an admin command renders. */
    public synchronized List<Snapshot> snapshot() {
        List<Snapshot> list = new ArrayList<>();
        servers.forEach((id, entry) -> {
            int free = entry.free.get();
            list.add(new Snapshot(id, entry.group, entry.capacity, free, free >= entry.capacity));
        });
        list.sort(Comparator.comparing(Snapshot::serverId));
        return list;
    }

    /** A match ended on the server: give the slot back. */
    public synchronized void releaseSlot(String serverId) {
        Entry entry = servers.get(serverId);
        if (entry != null) {
            entry.free.updateAndGet(free -> Math.min(entry.capacity, free + 1));
            entry.placedSinceReport.updateAndGet(placed -> Math.max(0, placed - 1));
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

    /** How many servers host each group. */
    public synchronized Map<String, Integer> serverCountByGroup() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Entry entry : servers.values()) {
            counts.merge(entry.group, 1, Integer::sum);
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
