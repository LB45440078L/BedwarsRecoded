package dev.bedwars.controller.pod;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Match-slot capacity: a server hosts many matches, and its own report of free match slots
 * is what the controller schedules against.
 *
 * <p>The distinction this file exists to protect: capacity is counted in <em>matches</em>.
 * Placing a player must not consume a match slot, because players pack into the same match —
 * if every placement burned a slot, a burst of players would be spread one-per-server, no
 * match would reach its minimum, and no match would ever start. (Reported live: two players
 * joined, each landed in a different match, and neither match began.)
 */
class ServerRegistryTest {

    @Test
    void placingPlayersDoesNotConsumeMatchSlots() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 3);

        assertThat(registry.freeSlots("solo")).isEqualTo(3);
        for (int i = 0; i < 6; i++) {
            assertThat(registry.allocate("solo")).contains("server-1");
        }
        // Six players later the server still has the three matches it always had: it is the
        // server's own report that decides when they are gone.
        assertThat(registry.freeSlots("solo")).isEqualTo(3);
        registry.updateFreeSlots("server-1", 0);
        assertThat(registry.allocate("solo")).isEmpty();
    }

    @Test
    void aBurstOfPlayersStaysOnOneServer() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("s1", "solo", 4);
        registry.register("s2", "solo", 4);

        // Everyone who queues in the same moment must end up together, or the match they
        // join never reaches its minimum and nobody is ever placed.
        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            registry.allocate("solo").ifPresent(chosen::add);
        }
        assertThat(chosen).containsOnly("s1");
    }

    @Test
    void reRegisteringAServerResetsItsSlots() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("server-1", "solo", 2);
        registry.updateFreeSlots("server-1", 1);
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
        registry.updateFreeSlots("server-1", 1);

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

        // "Idle" means "no match running", and only the server can say that. Placing a player
        // on it does not make it busy -- the match they joined does.
        registry.allocate("solo");
        assertThat(registry.idleServers()).isEqualTo(2);

        registry.updateFreeSlots("s1", 1);
        assertThat(registry.idleServers()).isEqualTo(1);
    }

    @Test
    void allocationPacksTheBusiestServerFirst() {
        ServerRegistry registry = new ServerRegistry();
        registry.register("s1", "solo", 5);
        registry.register("s2", "solo", 5);
        // s2 is the busiest: one match slot left.
        registry.updateFreeSlots("s2", 1);
        assertThat(registry.allocate("solo")).contains("s2");
        // It fills that last slot, so the next player goes to the only server with room.
        registry.updateFreeSlots("s2", 0);
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
        // server-2 is hosting a match, so it reports one slot free; server-1 is idle.
        registry.updateFreeSlots("server-2", 1);

        var snapshot = registry.snapshot();
        assertThat(snapshot).extracting(ServerRegistry.Snapshot::serverId)
                .containsExactly("server-1", "server-2");
        assertThat(snapshot.get(0).freeSlots()).isEqualTo(3);
        assertThat(snapshot.get(0).idle()).isTrue();
        assertThat(snapshot.get(1).freeSlots()).isEqualTo(1);
        assertThat(snapshot.get(1).idle()).isFalse();
    }
}
