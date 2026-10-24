package org.fleetloom.traffic.core;

import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.contracts.fleet.RouteCommandDto;

/** Outbound port to the fleet service. Implementations throw on delivery failure. */
public interface FleetGateway {

    OrderRefDto sendRoute(String vehicleCode, RouteCommandDto route);

    OrderRefDto extendRoute(String vehicleCode, RouteCommandDto route);

    void cancelOrder(String vehicleCode);
}
