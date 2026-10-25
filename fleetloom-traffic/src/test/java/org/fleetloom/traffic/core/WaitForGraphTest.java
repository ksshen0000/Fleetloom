package org.fleetloom.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WaitForGraphTest {

    @Test
    void chainsAreNoDeadlock() {
        assertThat(TrafficController.onCycles(Map.of("A", Set.of("B"), "B", Set.of("C")))).isEmpty();
    }

    @Test
    void findsOnlyTheVehiclesOnACycle() {
        // D waits for the cycle A -> B -> C -> A but is not part of it
        Map<String, Set<String>> waitsFor = Map.of("A", Set.of("B"), "B", Set.of("C"), "C", Set.of("A"), "D", Set.of("A"));

        assertThat(TrafficController.onCycles(waitsFor)).containsExactlyInAnyOrder("A", "B", "C");
    }

    /** A vehicle that lets two others pass waits for both; a cycle through either one counts. */
    @Test
    void followsEveryVehicleOneWaitsFor() {
        Map<String, Set<String>> waitsFor = Map.of("HOLD", Set.of("X", "Y"), "Y", Set.of("Z"), "Z", Set.of("HOLD"));

        assertThat(TrafficController.onCycles(waitsFor)).containsExactlyInAnyOrder("HOLD", "Y", "Z");
    }
}
