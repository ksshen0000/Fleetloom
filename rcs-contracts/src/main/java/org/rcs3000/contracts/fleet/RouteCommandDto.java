package org.rcs3000.contracts.fleet;

import java.util.List;

/**
 * A route for a vehicle, independent of the vehicle protocol. {@code edges.get(i)} connects
 * {@code nodes.get(i)} and {@code nodes.get(i + 1)}. For an update, the first node must be the
 * current decision point (the last released node sent so far).
 */
public record RouteCommandDto(String mapCode, List<RouteNodeDto> nodes, List<RouteEdgeDto> edges) {
}
