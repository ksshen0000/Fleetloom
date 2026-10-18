package org.rcs3000.traffic.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rcs3000.contracts.map.PointType;
import org.rcs3000.contracts.map.PositionDto;

/**
 * Directed graph of one published map version, as used for planning and reservations. Disabled paths
 * and points inside forbidden areas are left out. A bidirectional path appears as two edges that
 * share the same {@code pathCode}, and therefore the same reservation resource.
 */
public final class RoadGraph {

    private final String mapCode;
    private final int version;
    private final Map<String, Node> nodes;
    private final Map<String, List<Edge>> outgoing;
    private final double minWeight;

    RoadGraph(String mapCode, int version, Map<String, Node> nodes, Map<String, List<Edge>> outgoing) {
        this.mapCode = mapCode;
        this.version = version;
        this.nodes = Map.copyOf(nodes);
        this.outgoing = Map.copyOf(outgoing);
        this.minWeight = outgoing.values().stream().flatMap(List::stream).mapToDouble(Edge::weight).min().orElse(1);
    }

    public String mapCode() {
        return mapCode;
    }

    public int version() {
        return version;
    }

    public Optional<Node> node(String code) {
        return Optional.ofNullable(nodes.get(code));
    }

    public boolean contains(String code) {
        return nodes.containsKey(code);
    }

    public Collection<Node> nodes() {
        return nodes.values();
    }

    public List<Edge> outgoing(String from) {
        return outgoing.getOrDefault(from, List.of());
    }

    public Optional<Edge> edge(String from, String to) {
        return outgoing(from).stream().filter(e -> e.to().equals(to)).findFirst();
    }

    /** Smallest weight factor in the graph; keeps the A* heuristic admissible. */
    double minWeight() {
        return minWeight;
    }

    /** @param mutexAreas codes of the mutex areas the point lies in */
    public record Node(String code, double x, double y, PointType type, Set<String> mutexAreas) {

        double distanceTo(Node other) {
            return Math.hypot(other.x - x, other.y - y);
        }
    }

    /**
     * @param controlPoints Bezier control points oriented from {@code from} to {@code to}; empty for lines
     * @param startHeading  heading in radians when leaving {@code from}
     * @param endHeading    heading in radians when arriving at {@code to}
     * @param mutexAreas    mutex areas the edge touches
     */
    public record Edge(String pathCode, String from, String to, double length, Double maxSpeed, double weight,
            List<PositionDto> controlPoints, double startHeading, double endHeading, Set<String> mutexAreas) {
    }
}
