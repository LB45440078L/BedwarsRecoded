package dev.bedwars.controller.queue;

import dev.bedwars.api.dto.PartyInfo;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.service.DispatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class QueueManagerTest {

    /** Allocator that hands out addresses from a fixed deque, then empties. */
    private static final class FakeAllocator implements QueueManager.PodAllocator {
        private final Deque<String> pods = new ArrayDeque<>();

        FakeAllocator(String... addresses) {
            for (String address : addresses) {
                pods.add(address);
            }
        }

        @Override
        public Optional<String> allocate(String arenaGroup) {
            return Optional.ofNullable(pods.pollFirst());
        }
    }

    private static QueueRequest solo(UUID player, int priority) {
        return new QueueRequest(player, "p", priority, Optional.of("solo"), Optional.empty(), 0L);
    }

    @Test
    void dispatchesImmediatelyWhenCapacityAvailable() {
        QueueManager manager = new QueueManager(new FakeAllocator("pod-1"), 500, 10_000);
        DispatchResult result = manager.enqueue(solo(UUID.randomUUID(), 0)).join();
        assertThat(result.successful()).isTrue();
        assertThat(result.podAddress()).isEqualTo("pod-1");
    }

    @Test
    void higherPriorityIsDispatchedFirst() {
        // No capacity at enqueue time, so both entries wait; then one pod appears.
        FakeAllocator allocator = new FakeAllocator();
        QueueManager manager = new QueueManager(allocator, 500, 10_000);
        UUID low = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        CompletableFuture<DispatchResult> lowFuture = manager.enqueue(solo(low, 1));
        CompletableFuture<DispatchResult> highFuture = manager.enqueue(solo(high, 10));
        assertThat(lowFuture.isDone()).isFalse();
        assertThat(highFuture.isDone()).isFalse();

        allocator.pods.add("pod-1");
        manager.drain();

        // Only one pod existed; drain() must have given it to the higher priority.
        assertThat(highFuture.join().successful()).isTrue();
        assertThat(lowFuture.isDone()).isFalse();
        assertThat(manager.totalDepth()).isEqualTo(1);
    }

    @Test
    void partyIsDispatchedAtomicallyToSinglePod() {
        QueueManager manager = new QueueManager(new FakeAllocator("pod-1"), 500, 10_000);
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartyInfo party = new PartyInfo("party-1", leader, List.of(leader, member));
        QueueRequest request = new QueueRequest(leader, "leader", 0, Optional.of("solo"), Optional.of(party), 0L);

        DispatchResult result = manager.enqueue(request).join();
        assertThat(result.members()).containsExactlyInAnyOrder(leader, member);
        assertThat(result.podAddress()).isEqualTo("pod-1");
    }

    @Test
    void depthTracksAndClears() {
        QueueManager manager = new QueueManager(new FakeAllocator(), 500, 10_000);
        manager.enqueue(solo(UUID.randomUUID(), 0));
        manager.enqueue(solo(UUID.randomUUID(), 0));
        assertThat(manager.totalDepth()).isEqualTo(2);
        assertThat(manager.depthByGroup()).containsEntry("solo", 2);

        UUID waiting = UUID.randomUUID();
        manager.enqueue(solo(waiting, 0));
        manager.dequeue(waiting);
        assertThat(manager.totalDepth()).isEqualTo(2);
    }

    @Test
    void prewarmTriggersAboveThreshold() {
        QueueManager manager = new QueueManager(new FakeAllocator(), 500, 10_000);
        manager.enqueue(solo(UUID.randomUUID(), 0));
        assertThat(manager.shouldPrewarm(1)).isTrue();
        assertThat(manager.shouldPrewarm(5)).isFalse();
    }

    @Test
    void backoffIsBoundedAndNonNegative() {
        QueueManager manager = new QueueManager(new FakeAllocator(), 500, 10_000);
        for (int attempts = 0; attempts < 30; attempts++) {
            long delay = manager.backoffMillis(attempts);
            assertThat(delay).isBetween(0L, 10_000L);
        }
    }

    @Test
    void waitingEntryIsCompletedWhenCapacityArrives() {
        FakeAllocator allocator = new FakeAllocator(); // no pods yet
        QueueManager manager = new QueueManager(allocator, 500, 10_000);
        CompletableFuture<DispatchResult> future = manager.enqueue(solo(UUID.randomUUID(), 0));
        assertThat(future.isDone()).isFalse();

        allocator.pods.add("pod-late");
        manager.drain();
        assertThat(future.join().podAddress()).isEqualTo("pod-late");
    }

    //  The reported stampede: the client retries on a short backoff, and every retry left
    //  its entry behind when the HTTP wait timed out. Depth is what pre-warming scales on,
    //  so the fleet grew one server per retry while the player was never placed.
    @Test
    void aQueueEntryCanBeTakenBackOut() {
        QueueManager manager = new QueueManager(group -> Optional.empty(), 50L, 500L);
        UUID player = UUID.randomUUID();
        QueueRequest request = new QueueRequest(player, "steve", 0, Optional.empty(), Optional.empty(), 1L);

        manager.enqueue(request);
        assertThat(manager.totalDepth()).isEqualTo(1);

        assertThat(manager.dequeue(player)).isEqualTo(1);
        assertThat(manager.totalDepth()).isZero();
        assertThat(manager.depthByGroup()).isEmpty();
    }

    @Test
    void dequeuingOnePlayerLeavesTheRestQueued() {
        QueueManager manager = new QueueManager(group -> Optional.empty(), 50L, 500L);
        UUID waiting = UUID.randomUUID();
        UUID leaving = UUID.randomUUID();
        manager.enqueue(new QueueRequest(waiting, "alex", 0, Optional.empty(), Optional.empty(), 1L));
        manager.enqueue(new QueueRequest(leaving, "steve", 0, Optional.empty(), Optional.empty(), 2L));

        assertThat(manager.dequeue(leaving)).isEqualTo(1);
        assertThat(manager.totalDepth()).isEqualTo(1);
        assertThat(manager.dequeue(UUID.randomUUID())).isZero();
    }

    @Test
    void aGroupLessRequestIsCountedOnceNotPerRetry() {
        // Ten retries of one waiting player, each abandoned as it times out: the depth
        // must return to zero rather than climbing to ten.
        QueueManager manager = new QueueManager(group -> Optional.empty(), 50L, 500L);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            manager.enqueue(new QueueRequest(player, "steve", 0, Optional.empty(), Optional.empty(), i));
            manager.dequeue(player);
        }
        assertThat(manager.totalDepth()).isZero();
    }
}