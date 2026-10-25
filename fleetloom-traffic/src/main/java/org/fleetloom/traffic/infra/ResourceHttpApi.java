package org.fleetloom.traffic.infra;

import java.util.List;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/** Declarative client of fleetloom-resource's map and vehicle registry API. */
@HttpExchange("/api/resource/v1")
public interface ResourceHttpApi {

    @GetExchange("/maps/{code}/active")
    ApiResponse<MapTopologyDto> activeTopology(@PathVariable String code);

    @GetExchange("/vehicles")
    ApiResponse<List<Vehicle>> vehicles();

    @GetExchange("/vehicle-types")
    ApiResponse<List<VehicleType>> vehicleTypes();

    record Vehicle(String code, String typeCode, String manufacturer, String serialNumber) {
    }

    record VehicleType(String code, double maxSpeed) {
    }
}
