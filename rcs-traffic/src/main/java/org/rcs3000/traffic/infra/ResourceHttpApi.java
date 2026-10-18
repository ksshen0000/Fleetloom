package org.rcs3000.traffic.infra;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Declarative client of rcs-resource's map API. */
@HttpExchange("/api/resource/v1/maps")
public interface ResourceHttpApi {

    @GetExchange("/{code}/active")
    ApiResponse<MapTopologyDto> activeTopology(@PathVariable String code);
}
