package org.fleetloom.contracts.traffic;

import java.time.Instant;

/** Emitted when a route changes status, e.g. when the vehicle arrived at its target. */
public record RouteProgressedEvent(String routeId, String vehicleCode, String targetPoint, RouteStatus status,
        String lastNodeId, String reason, Instant at) {

    public static final String TYPE = "RouteProgressed";
}
