package org.rcs3000.contracts.fleet;

import java.util.List;

/**
 * @param pointCode map point the node stands for; becomes the VDA 5050 nodeId
 * @param theta     required heading at the node in radians, or {@code null}
 * @param released  {@code true} for the part the vehicle may drive (base), {@code false} for the horizon
 */
public record RouteNodeDto(String pointCode, double x, double y, Double theta, boolean released,
        List<VehicleActionDto> actions) {
}
