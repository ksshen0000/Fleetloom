package org.rcs3000.traffic.core;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pluggable path search (requirement TRF-06). */
public interface PathPlanner {

    Optional<PlannedRoute> plan(RoadGraph graph, Request request);

    /**
     * @param maxSpeed  vehicle speed in m/s, used where a path has no lower limit
     * @param blocked   resources the route must not use
     * @param penalties extra seconds for using a resource, e.g. one held by a parked vehicle
     */
    record Request(String from, String to, double maxSpeed, Set<Resource> blocked, Map<Resource, Double> penalties) {

        public static Request simple(String from, String to, double maxSpeed) {
            return new Request(from, to, maxSpeed, Set.of(), Map.of());
        }
    }
}
