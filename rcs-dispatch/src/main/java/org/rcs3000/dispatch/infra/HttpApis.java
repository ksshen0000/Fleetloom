package org.rcs3000.dispatch.infra;

import java.util.List;
import java.util.Set;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.contracts.storage.DroppedOffDto;
import org.rcs3000.contracts.storage.PickedUpDto;
import org.rcs3000.contracts.storage.ReleaseLocksDto;
import org.rcs3000.contracts.storage.TransportLockDto;
import org.rcs3000.contracts.storage.TransportLockRequestDto;
import org.rcs3000.contracts.traffic.CostMatrixDto;
import org.rcs3000.contracts.traffic.CostQueryDto;
import org.rcs3000.contracts.traffic.RouteRequestDto;
import org.rcs3000.contracts.traffic.RouteViewDto;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** Declarative clients of the services dispatch talks to, with the response fields it reads. */
final class HttpApis {

    private HttpApis() {
    }

    @HttpExchange("/api/resource/v1")
    interface ResourceApi {

        @GetExchange("/carriers/{code}")
        ApiResponse<Carrier> carrier(@PathVariable String code);

        @GetExchange("/storage-locations/{code}")
        ApiResponse<Location> location(@PathVariable String code);

        @GetExchange("/storage-locations/available")
        ApiResponse<List<Location>> available(@RequestParam(required = false) String zone,
                @RequestParam(required = false) String carrierType, @RequestParam(required = false) String map,
                @RequestParam(required = false) String nearPoint);

        @PostExchange("/transports/locks")
        ApiResponse<TransportLockDto> lock(@RequestBody TransportLockRequestDto request);

        @PostExchange("/transports/picked-up")
        ApiResponse<Object> pickedUp(@RequestBody PickedUpDto request);

        @PostExchange("/transports/dropped-off")
        ApiResponse<Object> droppedOff(@RequestBody DroppedOffDto request);

        @PostExchange("/transports/release")
        ApiResponse<Object> release(@RequestBody ReleaseLocksDto request);

        @GetExchange("/vehicles")
        ApiResponse<List<Vehicle>> vehicles();

        @GetExchange("/carrier-types")
        ApiResponse<List<CarrierType>> carrierTypes();

        @GetExchange("/maps/{code}/active")
        ApiResponse<MapTopologyDto> map(@PathVariable String code);
    }

    @HttpExchange("/api/traffic/v1")
    interface TrafficApi {

        @PostExchange("/routes")
        ApiResponse<RouteViewDto> requestRoute(@RequestBody RouteRequestDto request);

        @DeleteExchange("/routes/{vehicleCode}")
        ApiResponse<RouteViewDto> cancel(@PathVariable String vehicleCode);

        @GetExchange("/routes/{vehicleCode}")
        ApiResponse<RouteViewDto> route(@PathVariable String vehicleCode);

        @PostExchange("/costs")
        ApiResponse<CostMatrixDto> costs(@RequestBody CostQueryDto query);
    }

    @HttpExchange("/api/fleet/v1/vehicles")
    interface FleetApi {

        @PostExchange("/{code}/stop-charging")
        ApiResponse<String> stopCharging(@PathVariable String code);
    }

    record Carrier(String code, String typeCode, String locationType, String locationRef) {
    }

    record Location(String code, String mapCode, String accessPoint) {
    }

    record Vehicle(String code, String typeCode, String manufacturer, String serialNumber, boolean enabled,
            double chargeThresholdLow, double chargeThresholdHigh) {
    }

    record CarrierType(String code, Set<String> compatibleVehicleTypes) {
    }
}
