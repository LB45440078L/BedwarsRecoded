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

    public void registerReady(String podId, String arenaGroup) {
        readyByGroup.computeIfAbsent(arenaGroup, key -> new ConcurrentLinkedDeque<>()).add(podId);
        podToGroup.put(podId, arenaGroup);
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