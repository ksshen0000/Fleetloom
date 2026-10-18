package org.rcs3000.traffic.infra;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Declarative client of rcs-fleet's internal vehicle API. */
@HttpExchange("/api/fleet/v1/vehicles")
public interface FleetHttpApi {

    @PostExchange("/{code}/orders")
    ApiResponse<OrderRefDto> sendRoute(@PathVariable String code, @RequestBody RouteCommandDto route);

    @PostExchange("/{code}/orders/current/updates")
    ApiResponse<OrderRefDto> extendRoute(@PathVariable String code, @RequestBody RouteCommandDto route);

    @PostExchange("/{code}/cancel-order")
    ApiResponse<String> cancelOrder(@PathVariable String code);
}
