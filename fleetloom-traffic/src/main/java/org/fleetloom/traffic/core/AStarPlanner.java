package org.fleetloom.traffic.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * A* over travel time (requirement TRF-02). Edge cost = length / speed × weight factor, plus a penalty
 * per radian of heading change at each node, plus caller-supplied penalties. The search state is
 * (node, incoming edge) so that turn penalties are exact; the heuristic is the straight-line distance at
 * full speed and the smallest weight factor, which never overestimates.
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
        RoadGraph.Node target = goal.get();
        Map<State, Double> best = new HashMap<>();
        Map<State, Step> previous = new HashMap<>();
        PriorityQueue<Candidate> open = new PriorityQueue<>();
        State initial = new State(request.from(), null);
        best.put(initial, 0.0);
        open.add(new Candidate(initial, heuristic(graph, start.get(), target, request.maxSpeed())));

        while (!open.isEmpty()) {
            Candidate current = open.poll();
            double g = best.get(current.state);
            if (current.f - heuristic(graph, graph.node(current.state.node).orElseThrow(), target, request.maxSpeed()) > g + 1e-9) {
                continue; // stale queue entry
            }
            if (current.state.node.equals(request.to())) {
                return Optional.of(reconstruct(current.state, previous, g));
            }
            for (RoadGraph.Edge edge : graph.outgoing(current.state.node)) {
                RoadGraph.Node next = graph.node(edge.to()).orElseThrow();
                if (isBlocked(request, Resource.of(edge)) || isBlocked(request, Resource.of(next))) {
                    continue;
                }
                double cost = g + edgeCost(edge, request) + turnCost(current.state.via, edge)
                        + penalty(request, Resource.of(edge)) + penalty(request, Resource.of(next));
                State nextState = new State(edge.to(), edge);
                if (cost < best.getOrDefault(nextState, Double.POSITIVE_INFINITY) - 1e-12) {
                    best.put(nextState, cost);
                    previous.put(nextState, new Step(current.state, edge));
                    open.add(new Candidate(nextState, cost + heuristic(graph, next, target, request.maxSpeed())));
                }
            }
        }
        return Optional.empty();
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

    private static double heuristic(RoadGraph graph, RoadGraph.Node from, RoadGraph.Node to, double maxSpeed) {
        return from.distanceTo(to) / maxSpeed * Math.min(1.0, graph.minWeight());
    }

    private static boolean isBlocked(Request request, java.util.Set<Resource> resources) {
        return resources.stream().anyMatch(request.blocked()::contains);
    }

    private static double penalty(Request request, java.util.Set<Resource> resources) {
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

    private record Candidate(State state, double f) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate o) {
            return Double.compare(f, o.f);
        }
    }
}
