package org.rcs3000.traffic.core;

import java.util.ArrayList;
import java.util.List;

import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.RouteEdgeDto;
import org.rcs3000.contracts.fleet.RouteNodeDto;

/** Route commands on {@link TestMaps#grid} row 0, sent straight to the fleet service. */
final class Routes {

    private Routes() {
    }

    static RouteCommandDto straight(String... points) {
        return withHorizon(points.length, points);
    }

    /** Only the first {@code released} points (and the edges between them) are base; the rest is horizon. */
    static RouteCommandDto withHorizon(int released, String... points) {
        List<RouteNodeDto> nodes = new ArrayList<>();
        List<RouteEdgeDto> edges = new ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            int col = points[i].charAt(2) - '0';
            nodes.add(new RouteNodeDto(points[i], col * 4.0, 0, null, i < released, List.of()));
            if (i > 0) {
                edges.add(new RouteEdgeDto(points[i - 1] + "-" + points[i], i < released, null, List.of(), List.of()));
            }
        }
        return new RouteCommandDto(TestMaps.MAP, nodes, edges);
    }
}
