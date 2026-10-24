package org.fleetloom.simulator.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.fleetloom.simulator.core.SiteMap;

class StartPointsTest {

    @Test
    void parkingFirstThenThroughRoadsThenDeadEndsAndNeverChargers() {
        // DOCK - A - B - C, with parking P and charger CH off B
        SiteMap map = new SiteMap("M", "1", Map.of(
                "DOCK", new SiteMap.Point(0, 0, "NORMAL"),
                "A", new SiteMap.Point(1, 0, "NORMAL"),
                "B", new SiteMap.Point(2, 0, "NORMAL"),
                "C", new SiteMap.Point(3, 0, "NORMAL"),
                "P", new SiteMap.Point(2, 1, "PARKING"),
                "CH", new SiteMap.Point(2, -1, "CHARGING")), Map.of(
                "1", new SiteMap.Path("DOCK", "A", List.of()),
                "2", new SiteMap.Path("A", "B", List.of()),
                "3", new SiteMap.Path("B", "C", List.of()),
                "4", new SiteMap.Path("B", "P", List.of()),
                "5", new SiteMap.Path("B", "CH", List.of())));

        assertThat(SimulatorController.startPoints(map)).containsExactly("P", "A", "B", "C", "DOCK");
    }
}
