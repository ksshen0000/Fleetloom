package org.fleetloom.contracts.traffic;

import java.time.Instant;
import java.util.List;

/**
 * A vehicle's route and how far it got.
 *
 * @param nodes         planned point codes, start first
 * @param passedIndex   index in {@code nodes} of the last point the vehicle traversed
 * @param releasedIndex index of the last point the vehicle may drive to (end of the base)
 * @param blockedBy     vehicle holding the next resource while the route waits, or {@code null}
 */
public record RouteViewDto(
        String routeId,
        String vehicleCode,
        String mapCode,
        String targetPoint,
        RouteStatus status,
        List<String> nodes,
        int passedIndex,
        int releasedIndex,
        String blockedBy,
        String blockedResource,
        double estimatedSeconds,
        Instant createdAt) {
}
