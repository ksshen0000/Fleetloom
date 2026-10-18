package org.rcs3000.resource.map.domain;

import java.util.List;
import java.util.Set;

import org.rcs3000.contracts.map.PathDirection;
import org.rcs3000.contracts.map.PathShape;
import org.rcs3000.contracts.map.VehicleOrientation;

/**
 * A traversable connection between two points, referenced by point code so that bindings survive
 * map version changes.
 */
public record MapPath(
        String code,
        String fromPoint,
        String toPoint,
        PathDirection direction,
        PathShape shape,
        List<Position> controlPoints,
        Double maxSpeed,
        VehicleOrientation orientation,
        Set<String> allowedVehicleTypes,
        double weightFactor,
        boolean enabled) {

    public MapPath {
        controlPoints = controlPoints == null ? List.of() : List.copyOf(controlPoints);
        allowedVehicleTypes = allowedVehicleTypes == null ? Set.of() : Set.copyOf(allowedVehicleTypes);
    }

    public boolean bidirectional() {
        return direction == PathDirection.BIDIRECTIONAL;
    }
}
