package org.fleetloom.contracts.fleet;

import java.util.List;

import org.fleetloom.contracts.map.PositionDto;

/**
 * The connection between two consecutive route nodes.
 *
 * @param pathCode      map path code; becomes the VDA 5050 edgeId
 * @param maxSpeed      m/s, or {@code null}
 * @param controlPoints the two Bezier control points of a curved path, otherwise empty
 */
public record RouteEdgeDto(String pathCode, boolean released, Double maxSpeed, List<PositionDto> controlPoints,
        List<VehicleActionDto> actions) {
}
