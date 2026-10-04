package dev.bedwars.controller.pod;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ready pool is capacity accounting: a pod counted twice would let one party
 * be routed to a pod that is already hosting a match.
 */
class ReadyPodRegistryTest {

    @Test
    void repeatedReadyReportForTheSamePodIsNotExtraCapacity() {
        ReadyPodRegistry registry = new ReadyPodRegistry();

        registry.registerReady("bedwars-solo-0", "solo");
        registry.registerReady("bedwars-solo-0", "solo"); // OpenKruise reused the name

        assertThat(registry.readyCount("solo")).isEqualTo(1);
        assertThat(registry.allocate("solo")).contains("bedwars-solo-0");
        assertThat(registry.allocate("solo")).isEmpty();
    }

    @Test
    void distinctPodsEachCountOnce() {
        ReadyPodRegistry registry = new ReadyPodRegistry();

        registry.registerReady("pod-a", "solo");
        registry.registerReady("pod-b", "solo");

        assertThat(registry.readyCount("solo")).isEqualTo(2);
        registry.markGone("pod-a");
        assertThat(registry.readyCount("solo")).isEqualTo(1);
        assertThat(registry.allocate("solo")).contains("pod-b");
    }

    @Test
    void reReportingUnderADifferentGroupMovesThePod() {
        ReadyPodRegistry registry = new ReadyPodRegistry();

        registry.registerReady("pod-a", "solo");
        registry.registerReady("pod-a", "doubles");

        assertThat(registry.readyCount("solo")).isZero();
        assertThat(registry.readyCount("doubles")).isEqualTo(1);
    }

    @Test
    void allocatedPodIsNeverReturnedToThePool() {
        ReadyPodRegistry registry = new ReadyPodRegistry();

        registry.registerReady("pod-a", "solo");
        registry.allocate("solo");

        assertThat(registry.readyCount("solo")).isZero();
        assertThat(registry.allocate("solo")).isEmpty();
    }
}