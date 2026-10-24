package org.fleetloom.contracts.traffic;

import java.util.List;

import org.fleetloom.contracts.fleet.VehicleActionDto;

/**
 * Asks the traffic service to bring a vehicle to a map point.
 *
 * @param targetActions actions to run on arrival, e.g. a pick; may be {@code null}
 */
public record RouteRequestDto(String vehicleCode, String targetPoint, List<VehicleActionDto> targetActions) {
}
