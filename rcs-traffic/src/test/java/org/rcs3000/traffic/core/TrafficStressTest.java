package org.rcs3000.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.RouteEdgeDto;
import org.rcs3000.contracts.fleet.RouteNodeDto;
import org.rcs3000.contracts.traffic.RouteViewDto;

class TrafficStressTest {

    /** Proves the distance monitor has teeth: the same crossing without traffic control is a collision. */
    @Test
    void withoutTrafficControlCrossingVehiclesCollide() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4)).vehicle("AGV-1", "P10").vehicle("AGV-2", "P01");
        h.run(0.2);

        h.fleet().sendRoute("AGV-1", line(4, 0, 8, "P10", "P11", "P12", true));
        h.fleet().sendRoute("AGV-2", line(4, 0, 8, "P01", "P11", "P21", false));
        h.run(30);

        assertThat(h.minDistance()).isLessThan(1.0);
    }

    /**
     * Four vehicles keep getting random targets on a 4×4 grid for ten simulated minutes. They must never
     * come closer than 2 m, and must keep making progress.
     */
    @Test
    void randomTrafficStaysSafeAndKeepsMoving() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(4, 4, 4))
                .vehicle("V1", "P00").vehicle("V2", "P03").vehicle("V3", "P30").vehicle("V4", "P33");
        h.run(0.2);
        Random random = new Random(7);
        List<String> vehicles = List.of("V1", "V2", "V3", "V4");
        Map<String, String> targets = new HashMap<>();
        int requests = 0;

        for (int second = 0; second < 600; second++) {
            for (String v : vehicles) {
                boolean idle = h.traffic().route(v).map(r -> !r.status().isActive()).orElse(true);
                if (!idle) {
                    continue;
                }
                Set<String> taken = new HashSet<>(targets.values());
                vehicles.forEach(o -> taken.add(h.vehicle(o).lastNodeId()));
                List<String> free = new ArrayList<>();
                for (int r = 0; r < 4; r++) {
                    for (int c = 0; c < 4; c++) {
                        if (!taken.contains(TestMaps.name(r, c))) {
                            free.add(TestMaps.name(r, c));
                        }
                    }
                }
                String target = free.get(random.nextInt(free.size()));
                try {
                    RouteViewDto route = h.traffic().requestRoute(v, target, null);
                    targets.put(v, route.targetPoint());
                    requests++;
                } catch (BizException e) {
                    // e.g. the vehicle is still rolling after a cancel; try again next second
                }
            }
            h.run(1);
        }

        long arrivals = h.progress.stream().filter(p -> p.status() == org.rcs3000.contracts.traffic.RouteStatus.ARRIVED).count();
        assertThat(h.minDistance()).isGreaterThan(2.0);
        assertThat(arrivals).isGreaterThan(40);
        assertThat(arrivals).isGreaterThanOrEqualTo(requests - vehicles.size());
    }

    private static RouteCommandDto line(double fixed, double from, double to, String a, String b, String c, boolean horizontal) {
        double mid = (from + to) / 2;
        List<RouteNodeDto> nodes = List.of(
                node(a, horizontal ? from : fixed, horizontal ? fixed : from),
                node(b, horizontal ? mid : fixed, horizontal ? fixed : mid),
                node(c, horizontal ? to : fixed, horizontal ? fixed : to));
        return new RouteCommandDto(TestMaps.MAP, nodes, List.of(
                new RouteEdgeDto(a + "-" + b, true, null, List.of(), List.of()),
                new RouteEdgeDto(b + "-" + c, true, null, List.of(), List.of())));
    }

    private static RouteNodeDto node(String code, double x, double y) {
        return new RouteNodeDto(code, x, y, null, true, List.of());
    }
}
