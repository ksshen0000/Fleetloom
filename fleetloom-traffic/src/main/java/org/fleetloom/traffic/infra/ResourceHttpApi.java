package org.fleetloom.traffic.infra;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Declarative client of fleetloom-resource's map API. */
@HttpExchange("/api/resource/v1/maps")
public interface ResourceHttpApi {

    @GetExchange("/{code}/active")
    ApiResponse<MapTopologyDto> activeTopology(@PathVariable String code);
}
