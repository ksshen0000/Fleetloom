package org.rcs3000.traffic.core;

import java.util.List;

/**
 * A path through the road graph.
 *
 * @param nodes   point codes, start first; {@code edges.get(i)} leads from {@code nodes.get(i)} to {@code nodes.get(i + 1)}
 * @param seconds estimated travel time including turn penalties
 */
public record PlannedRoute(List<String> nodes, List<RoadGraph.Edge> edges, double seconds, double length) {

    public PlannedRoute {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public String start() {
        return nodes.getFirst();
    }

    public String target() {
        return nodes.getLast();
    }

    public int lastIndex() {
        return nodes.size() - 1;
    }

    /** This route followed by {@code next}, which starts where this one ends. */
    PlannedRoute then(PlannedRoute next) {
        List<String> n = new java.util.ArrayList<>(nodes);
        n.addAll(next.nodes.subList(1, next.nodes.size()));
        List<RoadGraph.Edge> e = new java.util.ArrayList<>(edges);
        e.addAll(next.edges);
        return new PlannedRoute(n, e, seconds + next.seconds, length + next.length);
    }

    /** This route up to and including node {@code index}, followed by {@code tail} (which starts at that node). */
    PlannedRoute splice(int index, PlannedRoute tail) {
        List<String> n = new java.util.ArrayList<>(nodes.subList(0, index));
        n.addAll(tail.nodes);
        List<RoadGraph.Edge> e = new java.util.ArrayList<>(edges.subList(0, index));
        e.addAll(tail.edges);
        double prefixLength = edges.subList(0, index).stream().mapToDouble(RoadGraph.Edge::length).sum();
        return new PlannedRoute(n, e, seconds, prefixLength + tail.length);
    }
}
