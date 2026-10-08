package dev.bedwars.controller.pod;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Match-slot capacity: a server hosts many matches, allocation reserves one slot
 * atomically, and exhaustion/removal behave correctly.
 */
class ServerRegistryTest {

    @Test
    void allocationReservesOneMatchSlotAtATime() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 3);

        assertThat(registry.freeSlots("solo")).isEqualTo(3);
        assertThat(registry.allocate("solo")).contains("server-1");
        assertThat(registry.freeSlots("solo")).isEqualTo(2);
        assertThat(registry.allocate("solo")).contains("server-1");
        assertThat(registry.allocate("solo")).contains("server-1");
        assertThat(registry.freeSlots("solo")).isZero();
        // Exhausted server is no longer offered.
        assertThat(registry.allocate("solo")).isEmpty();
    }

    @Test
    void reRegisteringAServerResetsItsSlots() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 2);
        registry.allocate("solo");
        assertThat(registry.freeSlots("solo")).isEqualTo(1);

        // A server re-reporting READY (e.g. restarted) resets capacity.
        registry.register("server-1", "solo", 2);
        assertThat(registry.freeSlots("solo")).isEqualTo(2);
    }

    @Test
    void aServerCapacityReportIsAuthoritative() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 25);
        registry.updateFreeSlots("server-1", 4);
        assertThat(registry.freeSlots("solo")).isEqualTo(4);
        // Clamped to [0, capacity].
        registry.updateFreeSlots("server-1", 999);
        assertThat(registry.freeSlots("solo")).isEqualTo(25);
    }

    @Test
    void releasingASlotGivesCapacityBackWithoutDroppingTheServer() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 2);
        registry.allocate("solo");
        assertThat(registry.freeSlots("solo")).isEqualTo(1);

        registry.releaseSlot("server-1");

        assertThat(registry.freeSlots("solo")).isEqualTo(2);
        assertThat(registry.serverCount()).isEqualTo(1);
    }

    @Test
    void drainingRemovesTheServerEntirely() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 4);
        registry.markGone("server-1");
        assertThat(registry.serverCount()).isZero();
        assertThat(registry.freeSlots("solo")).isZero();
    }

    @Test
    void capacityIsTrackedPerArenaGroup() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("s1", "solo", 5);
        registry.register("s2", "doubles", 5);
        assertThat(registry.freeSlots("solo")).isEqualTo(5);
        assertThat(registry.freeSlots("doubles")).isEqualTo(5);
        assertThat(registry.freeSlotsByGroup()).containsEntry("solo", 5).containsEntry("doubles", 5);
    }

    @Test
    void idleServersAreThoseWithNoMatchRunning() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("s1", "solo", 2);
        registry.register("s2", "solo", 2);

        assertThat(registry.idleServers()).isEqualTo(2);

        // Reserve a slot on the busiest server -> that one is no longer fully idle.
        Optional<String> allocated = registry.allocate("solo");
        assertThat(allocated).isPresent();
        assertThat(registry.idleServers()).isEqualTo(1);
    }

    @Test
    void allocationPacksTheBusiestServerFirst() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("s1", "solo", 5);
        registry.register("s2", "solo", 5);
        // Use up s2 more than s1.
        registry.updateFreeSlots("s2", 1);
        // The busiest (fewest free) with room is s2; it has exactly one slot.
        assertThat(registry.allocate("solo")).contains("s2");
        // s2 is now exhausted, so the next allocation goes to the only server with room.
        assertThat(registry.allocate("solo")).contains("s1");
    }
    //  Reported from a live network: three players queued in solo, servers registered
    //  as "solo", and NOT ONE of them was ever placed -- while the fleet kept growing.
    //  The lobby asks for a match without naming a group, the controller passed the
    //  placeholder "any" straight through, and an exact-match lookup found nothing.
    @Test
    void aRequestThatNamesNoGroupIsServedByAnyServer() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 3);

        assertThat(registry.allocate("any")).contains("server-1");
        assertThat(registry.allocate(null)).contains("server-1");
        assertThat(registry.allocate("")).contains("server-1");
        assertThat(registry.freeSlots("solo")).isZero();
    }

    @Test
    void aNamedGroupIsStillMatchedExactly() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 2);
        registry.register("server-2", "doubles", 2);

        assertThat(registry.allocate("doubles")).contains("server-2");
        assertThat(registry.freeSlots("solo")).isEqualTo(2);   // untouched
    }

    @Test
    void snapshotReportsEveryServerForOperatorViews() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-2", "solo", 2);
        registry.register("server-1", "solo", 3);
        registry.allocate("solo");      // server-1 is now busy

        var snapshot = registry.snapshot();
        assertThat(snapshot).extracting(ServerRegistry.Snapshot::serverId)
                .containsExactly("server-1", "server-2");
        // Allocation packs the fullest server first, so server-2 (the 2-slot one) took it.
        assertThat(snapshot.get(0).freeSlots()).isEqualTo(3);
        assertThat(snapshot.get(0).idle()).isTrue();
        assertThat(snapshot.get(1).freeSlots()).isEqualTo(1);
        assertThat(snapshot.get(1).idle()).isFalse();
    }
}
