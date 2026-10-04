package dev.bedwars.controller.queue;

import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.service.DispatchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Priority queue with party-cohesive, capacity-aware dispatch.
 *
 * <p>Each enqueue produces exactly one {@link CompletableFuture} that completes
 * once: with a pod address when a slot is found, or never (the HTTP layer races
 * it against a timeout and returns a retry hint). A party is one entry, so it is
 * dispatched atomically to a single pod or not at all — parties are never split.
 *
 * <p>{@link #drain()} is called whenever capacity may have changed (a pod became
 * READY, or KEDA scaled a GameServerSet up) and after every enqueue.
 */
public final class QueueManager {

    /** Returns a ready pod address for the group, or empty if none is available. */
    @FunctionalInterface
    public interface PodAllocator {
        Optional<String> allocate(String arenaGroup);
    }

    private record Entry(QueueRequest request, CompletableFuture<DispatchResult> result) {
    }

    private static final Comparator<Entry> PRIORITY = Comparator
            .comparingInt((Entry e) -> e.request().priority()).reversed()
            .thenComparingLong(e -> e.request().requestedAtMillis());

    private final PodAllocator allocator;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Entry> pending = new ArrayList<>();
    private final Map<String, Integer> depthByGroup = new ConcurrentHashMap<>();
    private final AtomicInteger totalDepth = new AtomicInteger();

    public QueueManager(PodAllocator allocator, long baseBackoffMillis, long maxBackoffMillis) {
        this.allocator = allocator;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
    }

    public CompletableFuture<DispatchResult> enqueue(QueueRequest request) {
        CompletableFuture<DispatchResult> result = new CompletableFuture<>();
        lock.lock();
        try {
            pending.add(new Entry(request, result));
            depthByGroup.merge(groupOf(request), 1, Integer::sum);
            totalDepth.incrementAndGet();
        } finally {
            lock.unlock();
        }
        drain();
        return result;
    }

    public void dequeue(UUID player) {
        lock.lock();
        try {
            pending.removeIf(entry -> {
                if (!matches(entry.request(), player)) {
                    return false;
                }
                entry.result().complete(DispatchResult.retry(0L));
                decrement(entry.request());
                return true;
            });
        } finally {
            lock.unlock();
        }
    }

    /** Attempts to place as many waiting entries as capacity allows. */
    public void drain() {
        lock.lock();
        try {
            pending.sort(PRIORITY);
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                Optional<String> pod = allocator.allocate(groupOf(entry.request()));
                if (pod.isEmpty()) {
                    break; // no capacity: leave the rest queued, in priority order
                }
                iterator.remove();
                decrement(entry.request());
                List<UUID> members = entry.request().party()
                        .map(party -> party.members())
                        .orElseGet(() -> List.of(entry.request().player()));
                entry.result().complete(new DispatchResult(pod.get(), null, members, 0L));
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean matches(QueueRequest request, UUID player) {
        return request.player().equals(player)
                || request.party().map(party -> party.contains(player)).orElse(false);
    }

    private static String groupOf(QueueRequest request) {
        return request.preferredGroup().orElse("any");
    }

    private void decrement(QueueRequest request) {
        depthByGroup.computeIfPresent(groupOf(request), (key, value) -> value <= 1 ? null : value - 1);
        totalDepth.decrementAndGet();
    }

    /** Exponential backoff with jitter, capped at {@code maxBackoffMillis}. */
    public long backoffMillis(int attempts) {
        long exponential = Math.min(maxBackoffMillis, baseBackoffMillis * (1L << Math.min(attempts, 10)));
        long jitter = (long) (Math.random() * baseBackoffMillis);
        return Math.min(maxBackoffMillis, exponential + jitter);
    }

    public Map<String, Integer> depthByGroup() {
        return Map.copyOf(depthByGroup);
    }

    public int totalDepth() {
        return totalDepth.get();
    }

    public boolean shouldPrewarm(int threshold) {
        return totalDepth.get() >= threshold;
    }
}