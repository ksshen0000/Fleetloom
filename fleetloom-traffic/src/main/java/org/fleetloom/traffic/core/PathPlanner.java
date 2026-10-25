package org.fleetloom.traffic.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pluggable path search (requirement TRF-06). */
public interface PathPlanner {

    Optional<PlannedRoute> plan(RoadGraph graph, Request request);

    /**
     * Travel seconds from {@code from} to each of {@code targets} without penalties, as {@link #plan}
     * would find them for a vehicle of that type; unreachable targets are left out. Planners that can
     * answer all targets with one search override this.
     */
    default Map<String, Double> costs(RoadGraph graph, String from, List<String> targets, double maxSpeed,
            String vehicleType) {
        Map<String, Double> result = new HashMap<>();
        for (String to : targets) {
            plan(graph, new Request(from, to, maxSpeed, Set.of(), Map.of(), Map.of(), vehicleType))
                    .ifPresent(r -> result.put(to, r.seconds()));
        }
        return result;
    }

    default Map<String, Double> costs(RoadGraph graph, String from, List<String> targets, double maxSpeed) {
        return costs(graph, from, targets, maxSpeed, null);
    }

    /**
     * @param maxSpeed      vehicle speed in m/s, used where a path has no lower limit
     * @param blocked       resources the route must not use
     * @param penalties     extra seconds for using a resource, e.g. one held by a parked vehicle
     * @param edgePenalties extra seconds for driving an edge, i.e. a path in one direction, e.g. against
     *                      the way other vehicles are going to drive it
     * @param vehicleType   type code of the vehicle, so paths closed to it are left out; {@code null}
     *                      when unknown, it then only uses paths open to all types
     */
    record Request(String from, String to, double maxSpeed, Set<Resource> blocked, Map<Resource, Double> penalties,
            Map<RoadGraph.Edge, Double> edgePenalties, String vehicleType) {

        public Request(String from, String to, double maxSpeed, Set<Resource> blocked, Map<Resource, Double> penalties) {
            this(from, to, maxSpeed, blocked, penalties, Map.of(), null);
        }

        public Request(String from, String to, double maxSpeed, Set<Resource> blocked, Map<Resource, Double> penalties,
                Map<RoadGraph.Edge, Double> edgePenalties) {
            this(from, to, maxSpeed, blocked, penalties, edgePenalties, null);
        }

        public static Request simple(String from, String to, double maxSpeed) {
            return new Request(from, to, maxSpeed, Set.of(), Map.of());
        }
    }
}
