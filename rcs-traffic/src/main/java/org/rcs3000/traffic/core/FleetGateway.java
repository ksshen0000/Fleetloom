package org.rcs3000.traffic.core;

import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.RouteCommandDto;

/** Outbound port to the fleet service. Implementations throw on delivery failure. */
public interface FleetGateway {

    OrderRefDto sendRoute(String vehicleCode, RouteCommandDto route);

    OrderRefDto extendRoute(String vehicleCode, RouteCommandDto route);

    void cancelOrder(String vehicleCode);
}
