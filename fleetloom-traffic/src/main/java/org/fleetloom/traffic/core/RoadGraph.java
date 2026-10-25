package org.fleetloom.traffic.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

import org.fleetloom.contracts.map.PointType;
import org.fleetloom.contracts.map.PositionDto;

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
    private final Map<String, Corridor> corridorByPath;
    private final Map<String, Integer> index = new HashMap<>();
    private final List<List<Edge>> incoming = new ArrayList<>();
    /** goal → {@link #weightedDistance} of every node, by index; the most recently used goals only */
    private final Map<String, double[]> distancesTo = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
            return size() > DISTANCE_CACHE_GOALS;
        }
    };

    private static final int DISTANCE_CACHE_GOALS = 512;

    RoadGraph(String mapCode, int version, Map<String, Node> nodes, Map<String, List<Edge>> outgoing) {
        this.mapCode = mapCode;
        this.version = version;
        this.nodes = Map.copyOf(nodes);
        this.outgoing = Map.copyOf(outgoing);
        this.corridorByPath = Map.copyOf(findCorridors());
        for (String code : this.nodes.keySet().stream().sorted().toList()) {
            index.put(code, incoming.size());
            incoming.add(new ArrayList<>());
        }
        this.outgoing.values().stream().flatMap(List::stream)
                .filter(e -> index.containsKey(e.to()))
                .forEach(e -> incoming.get(index.get(e.to())).add(e));
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

    /**
     * The least sum of length × weight factor over the edges of any way from {@code from} to
     * {@code goal}, {@link Double#POSITIVE_INFINITY} if there is none. Divided by the speed it is a
     * lower bound of the travel time that never overestimates (turns, speed limits and penalties only
     * add to it), so A* can use it as an exact-distance heuristic. Found by one Dijkstra search
     * backwards from the goal and kept for the goals used most recently, as in the Reverse Resumable
     * A* of Silver, "Cooperative Pathfinding" (AIIDE 2005). Thread-safe.
     */
    public double weightedDistance(String from, String goal) {
        Integer i = index.get(from);
        if (i == null || !index.containsKey(goal)) {
            return Double.POSITIVE_INFINITY;
        }
        double[] distances;
        synchronized (distancesTo) {
            distances = distancesTo.get(goal);
        }
        if (distances == null) {
            distances = backwardsFrom(goal);
            synchronized (distancesTo) {
                distancesTo.put(goal, distances);
            }
        }
        return distances[i];
    }

    private double[] backwardsFrom(String goal) {
        double[] distances = new double[incoming.size()];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        int start = index.get(goal);
        distances[start] = 0;
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        open.add(new double[] {0, start});
        while (!open.isEmpty()) {
            double[] current = open.poll();
            int node = (int) current[1];
            if (current[0] > distances[node]) {
                continue;
            }
            for (Edge e : incoming.get(node)) {
                Integer previous = index.get(e.from());
                double d = current[0] + e.length() * e.weight();
                if (previous != null && d < distances[previous]) {
                    distances[previous] = d;
                    open.add(new double[] {d, previous});
                }
            }
        }
        return distances;
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
     * @param vehicleTypes  vehicle type codes allowed on the path; empty means all
     */
    public record Edge(String pathCode, String from, String to, double length, Double maxSpeed, double weight,
            List<PositionDto> controlPoints, double startHeading, double endHeading, Set<String> mutexAreas,
            Set<String> vehicleTypes) {

        public Edge(String pathCode, String from, String to, double length, Double maxSpeed, double weight,
                List<PositionDto> controlPoints, double startHeading, double endHeading, Set<String> mutexAreas) {
            this(pathCode, from, to, length, maxSpeed, weight, controlPoints, startHeading, endHeading, mutexAreas,
                    Set.of());
        }

        /** A vehicle of unknown type ({@code null}) may only use paths open to all types. */
        public boolean allows(String vehicleType) {
            return vehicleTypes.isEmpty() || vehicleType != null && vehicleTypes.contains(vehicleType);
        }
    }
}
