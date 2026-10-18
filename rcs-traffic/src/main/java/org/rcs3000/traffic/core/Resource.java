package org.rcs3000.traffic.core;

import java.util.HashSet;
import java.util.Set;

/** Something only one vehicle may hold at a time: a point, a path (both directions) or a mutex area. */
public record Resource(Kind kind, String code) {

    public enum Kind { NODE, PATH, AREA }

    public static Resource node(String code) {
        return new Resource(Kind.NODE, code);
    }

    public static Resource path(String code) {
        return new Resource(Kind.PATH, code);
    }

    /** Resources needed to stand on {@code node}. */
    static Set<Resource> of(RoadGraph.Node node) {
        Set<Resource> resources = new HashSet<>();
        resources.add(node(node.code()));
        node.mutexAreas().forEach(a -> resources.add(new Resource(Kind.AREA, a)));
        return resources;
    }

    /** Resources needed to drive along {@code edge}, excluding its end node. */
    static Set<Resource> of(RoadGraph.Edge edge) {
        Set<Resource> resources = new HashSet<>();
        resources.add(path(edge.pathCode()));
        edge.mutexAreas().forEach(a -> resources.add(new Resource(Kind.AREA, a)));
        return resources;
    }

    @Override
    public String toString() {
        return kind + ":" + code;
    }
}
