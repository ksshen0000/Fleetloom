package org.rcs3000.fleet.core;

import java.util.ArrayList;
import java.util.List;

import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.RouteEdgeDto;
import org.rcs3000.contracts.fleet.RouteNodeDto;
import org.rcs3000.contracts.fleet.VehicleActionDto;

/** Builds route commands for tests: {@code Routes.on("M").node("A", 0, 0).node("B", 10, 0).build()}. */
public final class Routes {

    private final String mapCode;
    private final List<RouteNodeDto> nodes = new ArrayList<>();
    private final List<RouteEdgeDto> edges = new ArrayList<>();
    private boolean released = true;

    private Routes(String mapCode) {
        this.mapCode = mapCode;
    }

    public static Routes on(String mapCode) {
        return new Routes(mapCode);
    }

    /** Nodes and edges added after this call form the horizon. */
    public Routes horizon() {
        released = false;
        return this;
    }

    public Routes node(String point, double x, double y, VehicleActionDto... actions) {
        if (!nodes.isEmpty()) {
            edges.add(new RouteEdgeDto(nodes.getLast().pointCode() + point, released, null, List.of(), List.of()));
        }
        nodes.add(new RouteNodeDto(point, x, y, null, released, List.of(actions)));
        return this;
    }

    public RouteCommandDto build() {
        return new RouteCommandDto(mapCode, nodes, edges);
    }
}
