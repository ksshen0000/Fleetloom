package org.fleetloom.traffic.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* over travel time (requirement TRF-02). Edge cost = length / speed × weight factor, plus a penalty
 * per radian of heading change at each node, plus caller-supplied penalties. The search state is
 * (node, incoming edge) so that turn penalties are exact. The heuristic is the exact weighted distance
 * to the goal at full speed ({@link RoadGraph#weightedDistance}), which never overestimates and, unlike
 * the straight-line distance, knows about walls and one-way paths: on warehouse layouts A* then expands
 * little more than the route itself. Paths closed to the vehicle's type are left out; the heuristic
 * ignores such restrictions and so stays a lower bound.
 */
public final class AStarPlanner implements PathPlanner {

    private final double turnPenaltyPerRadian;

    /** @param turnPenaltyPerRadian seconds added per radian of heading change, e.g. 2 */
    public AStarPlanner(double turnPenaltyPerRadian) {
        this.turnPenaltyPerRadian = turnPenaltyPerRadian;
    }

    @Override
    public Optional<PlannedRoute> plan(RoadGraph graph, Request request) {
        Optional<RoadGraph.Node> start = graph.node(request.from());
        Optional<RoadGraph.Node> goal = graph.node(request.to());
        if (start.isEmpty() || goal.isEmpty() || isBlocked(request, Resource.of(goal.get()))) {
            return Optional.empty();
        }
        if (request.from().equals(request.to())) {
            return Optional.of(new PlannedRoute(List.of(request.from()), List.of(), 0, 0));
        }
        double h0 = heuristic(graph, request.from(), request);
        if (Double.isInfinite(h0)) {
            return Optional.empty();
        }
        Map<State, Double> best = new HashMap<>();
        Map<State, Step> previous = new HashMap<>();
        PriorityQueue<Candidate> open = new PriorityQueue<>();
        State initial = new State(request.from(), null);
        best.put(initial, 0.0);
        open.add(new Candidate(initial, 0, h0));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            double g = current.g;
            if (g > best.get(current.state) + 1e-9) {
                continue; // stale queue entry
            }
            if (current.state.node.equals(request.to())) {
                return Optional.of(reconstruct(current.state, previous, g));
            }
            for (RoadGraph.Edge edge : graph.outgoing(current.state.node)) {
                RoadGraph.Node next = graph.node(edge.to()).orElseThrow();
                if (!edge.allows(request.vehicleType()) || isBlocked(request, Resource.of(edge))
                        || isBlocked(request, Resource.of(next))) {
                    continue;
                }
                double h = heuristic(graph, edge.to(), request);
                if (Double.isInfinite(h)) {
                    continue; // the goal cannot be reached from there
                }
                double cost = g + edgeCost(edge, request) + turnCost(current.state.via, edge)
                        + penalty(request, Resource.of(edge)) + penalty(request, Resource.of(next))
                        + request.edgePenalties().getOrDefault(edge, 0.0);
                State nextState = new State(edge.to(), edge);
                if (cost < best.getOrDefault(nextState, Double.POSITIVE_INFINITY) - 1e-12) {
                    best.put(nextState, cost);
                    previous.put(nextState, new Step(current.state, edge));
                    open.add(new Candidate(nextState, cost, cost + h));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * One Dijkstra search from {@code from} over the same (node, incoming edge) states as {@link #plan},
     * stopped once every target is settled: the costs equal those of planning each target on its own.
     */
    @Override
    public Map<String, Double> costs(RoadGraph graph, String from, List<String> targets, double maxSpeed,
            String vehicleType) {
        Map<String, Double> result = new HashMap<>();
        if (!graph.contains(from)) {
            return result;
        }
        Set<String> remaining = new HashSet<>();
        for (String t : targets) {
            if (t.equals(from)) {
                result.put(t, 0.0);
            } else if (graph.contains(t)) {
                remaining.add(t);
            }
        }
        Request request = new Request(from, from, maxSpeed, Set.of(), Map.of(), Map.of(), vehicleType);
        Map<State, Double> best = new HashMap<>();
        PriorityQueue<Candidate> open = new PriorityQueue<>();
        State initial = new State(from, null);
        best.put(initial, 0.0);
        open.add(new Candidate(initial, 0, 0));
        while (!open.isEmpty() && !remaining.isEmpty()) {
            Candidate current = open.poll();
            if (current.g > best.get(current.state) + 1e-9) {
                continue;
            }
            if (remaining.remove(current.state.node)) {
                result.put(current.state.node, current.g);
            }
            for (RoadGraph.Edge edge : graph.outgoing(current.state.node)) {
                if (!edge.allows(vehicleType)) {
                    continue;
                }
                double cost = current.g + edgeCost(edge, request) + turnCost(current.state.via, edge);
                State nextState = new State(edge.to(), edge);
                if (cost < best.getOrDefault(nextState, Double.POSITIVE_INFINITY) - 1e-12) {
                    best.put(nextState, cost);
                    open.add(new Candidate(nextState, cost, cost));
                }
            }
        }
        return result;
    }

    /** Travel time of an edge without turn penalties. */
    static double edgeCost(RoadGraph.Edge edge, Request request) {
        double speed = edge.maxSpeed() == null ? request.maxSpeed() : Math.min(edge.maxSpeed(), request.maxSpeed());
        return edge.length() / speed * edge.weight();
    }

    private double turnCost(RoadGraph.Edge incoming, RoadGraph.Edge outgoing) {
        if (incoming == null) {
            return 0;
        }
        double diff = Math.abs(Math.IEEEremainder(outgoing.startHeading() - incoming.endHeading(), 2 * Math.PI));
        return diff * turnPenaltyPerRadian;
    }

    private static double heuristic(RoadGraph graph, String node, Request request) {
        return graph.weightedDistance(node, request.to()) / request.maxSpeed();
    }

    private static boolean isBlocked(Request request, Set<Resource> resources) {
        return resources.stream().anyMatch(request.blocked()::contains);
    }

    private static double penalty(Request request, Set<Resource> resources) {
        return resources.stream().mapToDouble(r -> request.penalties().getOrDefault(r, 0.0)).sum();
    }

    private static PlannedRoute reconstruct(State end, Map<State, Step> previous, double cost) {
        List<String> nodes = new ArrayList<>();
        List<RoadGraph.Edge> edges = new ArrayList<>();
        State s = end;
        nodes.add(s.node);
        while (previous.containsKey(s)) {
            Step step = previous.get(s);
            edges.add(step.edge);
            s = step.from;
            nodes.add(s.node);
        }
        Collections.reverse(nodes);
        Collections.reverse(edges);
        return new PlannedRoute(nodes, edges, cost, edges.stream().mapToDouble(RoadGraph.Edge::length).sum());
    }

    private record State(String node, RoadGraph.Edge via) {
    }

    private record Step(State from, RoadGraph.Edge edge) {
    }

    /** Ties on f go to the deeper state, which is closer to the goal: fewer expansions. */
    private record Candidate(State state, double g, double f) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate o) {
            int byF = Double.compare(f, o.f);
            return byF != 0 ? byF : Double.compare(o.g, g);
        }
    }
}
