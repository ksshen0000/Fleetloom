package org.rcs3000.traffic.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rcs3000.contracts.map.AreaDto;
import org.rcs3000.contracts.map.AreaType;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.contracts.map.PathDirection;
import org.rcs3000.contracts.map.PathDto;
import org.rcs3000.contracts.map.PointDto;
import org.rcs3000.contracts.map.PositionDto;

/** Builds a {@link RoadGraph} from the map service's topology (requirements TRF-01, TRF-03). */
public final class RoadGraphBuilder {

    private RoadGraphBuilder() {
    }

    public static RoadGraph build(MapTopologyDto topology) {
        List<AreaDto> forbidden = topology.areas().stream().filter(a -> a.type() == AreaType.FORBIDDEN).toList();
        List<AreaDto> mutex = topology.areas().stream().filter(a -> a.type() == AreaType.MUTEX).toList();

        Map<String, RoadGraph.Node> nodes = new HashMap<>();
        for (PointDto p : topology.points()) {
            if (forbidden.stream().anyMatch(a -> contains(a.polygon(), p.x(), p.y()))) {
                continue;
            }
            Set<String> areas = new HashSet<>();
            mutex.stream().filter(a -> contains(a.polygon(), p.x(), p.y())).forEach(a -> areas.add(a.code()));
            nodes.put(p.code(), new RoadGraph.Node(p.code(), p.x(), p.y(), p.type(), Set.copyOf(areas)));
        }

        Map<String, List<RoadGraph.Edge>> outgoing = new HashMap<>();
        for (PathDto path : topology.paths()) {
            RoadGraph.Node from = nodes.get(path.fromPoint());
            RoadGraph.Node to = nodes.get(path.toPoint());
            if (Boolean.FALSE.equals(path.enabled()) || from == null || to == null) {
                continue;
            }
            List<PositionDto> controls = path.controlPoints() == null ? List.of() : path.controlPoints();
            double length = path.length() != null && path.length() > 0 ? path.length() : from.distanceTo(to);
            double weight = path.weightFactor() == null ? 1.0 : path.weightFactor();
            Set<String> areas = new HashSet<>(from.mutexAreas());
            areas.addAll(to.mutexAreas());
            double midX = (from.x() + to.x()) / 2;
            double midY = (from.y() + to.y()) / 2;
            mutex.stream().filter(a -> contains(a.polygon(), midX, midY)).forEach(a -> areas.add(a.code()));

            add(outgoing, edge(path, from, to, controls, length, weight, areas));
            if (path.direction() == PathDirection.BIDIRECTIONAL) {
                add(outgoing, edge(path, to, from, controls.reversed(), length, weight, areas));
            }
        }
        return new RoadGraph(topology.mapCode(), topology.versionNo(), nodes, outgoing);
    }

    private static RoadGraph.Edge edge(PathDto path, RoadGraph.Node from, RoadGraph.Node to, List<PositionDto> controls,
            double length, double weight, Set<String> areas) {
        double startHeading;
        double endHeading;
        if (controls.size() == 2) {
            startHeading = Math.atan2(controls.get(0).y() - from.y(), controls.get(0).x() - from.x());
            endHeading = Math.atan2(to.y() - controls.get(1).y(), to.x() - controls.get(1).x());
        } else {
            startHeading = Math.atan2(to.y() - from.y(), to.x() - from.x());
            endHeading = startHeading;
        }
        return new RoadGraph.Edge(path.code(), from.code(), to.code(), length, path.maxSpeed(), weight,
                List.copyOf(controls), startHeading, endHeading, Set.copyOf(areas));
    }

    private static void add(Map<String, List<RoadGraph.Edge>> outgoing, RoadGraph.Edge edge) {
        outgoing.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
    }

    /** Ray casting point-in-polygon test. */
    static boolean contains(List<PositionDto> polygon, double x, double y) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            PositionDto a = polygon.get(i);
            PositionDto b = polygon.get(j);
            if ((a.y() > y) != (b.y() > y) && x < (b.x() - a.x()) * (y - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }
}
