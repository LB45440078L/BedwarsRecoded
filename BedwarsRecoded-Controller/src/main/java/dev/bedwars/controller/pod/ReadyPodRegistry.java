package dev.bedwars.controller.pod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tracks pods that have reported READY and are waiting to be allocated. Pods are
 * cattle: when a pod is allocated it is removed here and never returned to the
 * pool (its match is its last).
 */
public final class ReadyPodRegistry {

    private final Map<String, Deque<String>> readyByGroup = new ConcurrentHashMap<>();
    private final Map<String, String> podToGroup = new ConcurrentHashMap<>();

    /**
     * Marks a pod READY. Idempotent by pod id: OpenKruise reuses pod names
     * (a recreated {@code bedwars-solo-0} reports READY again), and a repeated
     * report must never inflate capacity or let the same pod be allocated twice.
     */
    public void registerReady(String podId, String arenaGroup) {
        String previous = podToGroup.put(podId, arenaGroup);
        Deque<String> queue = readyByGroup.computeIfAbsent(arenaGroup, key -> new ConcurrentLinkedDeque<>());
        if (arenaGroup.equals(previous)) {
            return; // already pooled under this group; the pod is not twice the capacity
        }
        if (previous != null) {
            Deque<String> old = readyByGroup.get(previous);
            if (old != null) {
                old.remove(podId);
            }
        }
        if (!queue.contains(podId)) {
            queue.add(podId);
        }
    }

    /** Removes and returns a ready pod for the group, if any. */
    public Optional<String> allocate(String arenaGroup) {
        Deque<String> queue = readyByGroup.get(arenaGroup);
        if (queue == null) {
            return Optional.empty();
        }
        String pod = queue.pollFirst();
        if (pod != null) {
            podToGroup.remove(pod);
        }
        return Optional.ofNullable(pod);
    }

    public void markGone(String podId) {
        String group = podToGroup.remove(podId);
        if (group != null) {
            Deque<String> queue = readyByGroup.get(group);
            if (queue != null) {
                queue.remove(podId);
            }
        }
    }

    public int readyCount(String arenaGroup) {
        Deque<String> queue = readyByGroup.get(arenaGroup);
        return queue == null ? 0 : queue.size();
    }

    public Map<String, Integer> readyByGroup() {
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        readyByGroup.forEach((group, queue) -> counts.put(group, queue.size()));
        return counts;
    }

    // Reserved for callers that need a snapshot without draining.
    Deque<String> peekQueue(String group) {
        return readyByGroup.getOrDefault(group, new ArrayDeque<>());
    }
}