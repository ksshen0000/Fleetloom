package org.fleetloom.contracts.map;

import java.util.List;
import java.util.Set;

/**
 * A traversable connection between two points, referenced by point code.
 *
 * @param controlPoints        the two Bezier control points when {@code shape == BEZIER}, otherwise empty
 * @param maxSpeed             speed limit in m/s, or {@code null} for the vehicle's own limit
 * @param allowedVehicleTypes  vehicle type codes allowed on this path; empty means all types
 * @param weightFactor         cost multiplier for path planning; {@code null} on input means 1.0
 * @param enabled              {@code null} on input means enabled
 * @param length               path length in meters, computed by the map service; omitted and ignored on input
 */
public record PathDto(
        String code,
        String fromPoint,
        String toPoint,
        PathDirection direction,
        PathShape shape,
        List<PositionDto> controlPoints,
        Double maxSpeed,
        VehicleOrientation orientation,
        Set<String> allowedVehicleTypes,
        Double weightFactor,
        Boolean enabled,
        Double length) {
}
