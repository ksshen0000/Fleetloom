package org.rcs3000.resource.vehicle.domain;

import java.time.Instant;
import java.util.Set;

/**
 * @param maxSpeed         m/s
 * @param maxLoad          kg
 * @param supportedActions VDA 5050 action types, e.g. pick, drop
 */
public record VehicleType(Long id, String code, String name, VehicleCategory category, double length, double width,
        double maxSpeed, double maxLoad, Set<String> supportedActions, Instant createdAt, Instant updatedAt) {

    public VehicleType {
        supportedActions = Set.copyOf(supportedActions);
    }
}
