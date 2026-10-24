package org.rcs3000.traffic.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

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
    private final Map<String, Corridor> corridorByPath;

    RoadGraph(String mapCode, int version, Map<String, Node> nodes, Map<String, List<Edge>> outgoing) {
        this.mapCode = mapCode;
        this.version = version;
        this.nodes = Map.copyOf(nodes);
        this.outgoing = Map.copyOf(outgoing);
        this.minWeight = outgoing.values().stream().flatMap(List::stream).mapToDouble(Edge::weight).min().orElse(1);
        this.corridorByPath = Map.copyOf(findCorridors());
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

    /** The single-lane corridor the path is part of, if any. */
    public Optional<Corridor> corridor(String pathCode) {
        return Optional.ofNullable(corridorByPath.get(pathCode));
    }

    public Collection<Corridor> corridors() {
        return Set.copyOf(corridorByPath.values());
    }

    /**
     * Chains of two or more bidirectional paths joined at points where nothing else meets: a vehicle
     * that enters one can only leave at either end, so two vehicles driving into it from both ends meet
     * head-on with no way past each other (a corner of a grid is the shortest such chain).
     */
    private Map<String, Corridor> findCorridors() {
        Map<String, Set<String>> pathsAt = new HashMap<>();
        Map<String, Edge> anyEdge = new HashMap<>();
        Map<String, Integer> directions = new HashMap<>();
        outgoing.values().stream().flatMap(List::stream).forEach(e -> {
            pathsAt.computeIfAbsent(e.from(), k -> new HashSet<>()).add(e.pathCode());
            pathsAt.computeIfAbsent(e.to(), k -> new HashSet<>()).add(e.pathCode());
            anyEdge.putIfAbsent(e.pathCode(), e);
            directions.merge(e.pathCode(), 1, Integer::sum);
        });
        Predicate<String> bidirectional = path -> directions.get(path) == 2;
        Predicate<String> inner = node -> pathsAt.get(node).size() == 2
                && pathsAt.get(node).stream().allMatch(bidirectional);

        Map<String, Corridor> result = new HashMap<>();
        Set<String> done = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (String path : anyEdge.keySet()) {
            if (done.contains(path) || !bidirectional.test(path)) {
                continue;
            }
            // walk to one end of the chain, then collect it from there to the other end
            Edge e = anyEdge.get(path);
            String node = e.from();
            String via = path;
            Set<String> seen = new HashSet<>();
            while (inner.test(node) && seen.add(node)) {
                String next = other(pathsAt.get(node), via);
                node = otherEnd(anyEdge.get(next), node);
                via = next;
            }
            List<String> chainNodes = new ArrayList<>(List.of(node));
            List<String> chainPaths = new ArrayList<>();
            String current = node;
            String step = via;
            while (step != null && !chainPaths.contains(step)) {
                chainPaths.add(step);
                current = otherEnd(anyEdge.get(step), current);
                chainNodes.add(current);
                step = inner.test(current) ? other(pathsAt.get(current), step) : null;
            }
            done.addAll(chainPaths);
            if (chainPaths.size() >= 2) {
                if (chainNodes.getFirst().compareTo(chainNodes.getLast()) > 0) {
                    Collections.reverse(chainNodes);
                    Collections.reverse(chainPaths);
                }
                // named after its ends; two corridors between the same ends are told apart by a number
                String name = chainNodes.getFirst() + "~" + chainNodes.getLast();
                String id = name;
                for (int n = 2; ids.contains(id); n++) {
                    id = name + "#" + n;
                }
                ids.add(id);
                Corridor c = new Corridor(id, chainNodes, chainPaths);
                chainPaths.forEach(p -> result.put(p, c));
            }
        }
        return result;
    }

    private static String other(Set<String> twoPaths, String path) {
        return twoPaths.stream().filter(p -> !p.equals(path)).findFirst().orElseThrow();
    }

    private static String otherEnd(Edge e, String node) {
        return e.from().equals(node) ? e.to() : e.from();
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
     * A single-lane stretch of road, see {@link #findCorridors()}.
     *
     * @param nodes points from one end to the other, ends included
     * @param paths path codes in the same order; {@code paths.get(i)} joins {@code nodes.get(i)} and {@code nodes.get(i + 1)}
     */
    public record Corridor(String id, List<String> nodes, List<String> paths) {

        public Corridor {
            nodes = List.copyOf(nodes);
            paths = List.copyOf(paths);
        }

        /** +1 if {@code edge} runs from the first node towards the last, -1 the other way. */
        public int direction(Edge edge) {
            int i = paths.indexOf(edge.pathCode());
            return nodes.get(i).equals(edge.from()) ? 1 : -1;
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
