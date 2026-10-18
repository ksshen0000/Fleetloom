package org.rcs3000.traffic.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.traffic.CostMatrixDto;
import org.rcs3000.contracts.traffic.CostQueryDto;
import org.rcs3000.contracts.traffic.ReservationDto;
import org.rcs3000.contracts.traffic.RouteViewDto;
import org.rcs3000.traffic.app.TrafficEngine;
import org.rcs3000.traffic.core.TrafficErrorCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traffic/v1")
public class TrafficApiController {

    private final TrafficEngine engine;

    public TrafficApiController(TrafficEngine engine) {
        this.engine = engine;
    }

    /** Moves a vehicle to a point, e.g. a manual move (VEH-05) or a dispatch step. */
    @PostMapping("/routes")
    public ApiResponse<RouteViewDto> requestRoute(@Valid @RequestBody RouteRequest request) {
        return ApiResponse.ok(engine.call(c -> c.requestRoute(request.vehicleCode(), request.targetPoint(),
                request.targetActions())));
    }

    @GetMapping("/routes")
    public ApiResponse<List<RouteViewDto>> routes() {
        return ApiResponse.ok(engine.call(c -> c.routes()));
    }

    @GetMapping("/routes/{vehicleCode}")
    public ApiResponse<RouteViewDto> route(@PathVariable String vehicleCode) {
        return ApiResponse.ok(engine.call(c -> c.route(vehicleCode)).orElseThrow(() ->
                new BizException(TrafficErrorCode.NO_ACTIVE_ROUTE, "vehicle %s has no route".formatted(vehicleCode))));
    }

    @DeleteMapping("/routes/{vehicleCode}")
    public ApiResponse<RouteViewDto> cancel(@PathVariable String vehicleCode) {
        return ApiResponse.ok(engine.call(c -> c.cancelRoute(vehicleCode)));
    }

    @PostMapping("/costs")
    public ApiResponse<CostMatrixDto> costs(@Valid @RequestBody CostQuery query) {
        return ApiResponse.ok(engine.call(c -> c.costs(new CostQueryDto(query.mapCode(), query.from(), query.to()))));
    }

    @GetMapping("/reservations")
    public ApiResponse<List<ReservationDto>> reservations() {
        return ApiResponse.ok(engine.call(c -> c.reservations()));
    }

    /** Drops every reservation of a vehicle (TRF-19); only for vehicles without an active route. */
    @PostMapping("/vehicles/{vehicleCode}/release")
    public ApiResponse<Void> release(@PathVariable String vehicleCode) {
        engine.call(c -> {
            c.forceRelease(vehicleCode);
            return null;
        });
        return ApiResponse.ok();
    }

    public record RouteRequest(@NotBlank String vehicleCode, @NotBlank String targetPoint,
            List<VehicleActionDto> targetActions) {
    }

    public record CostQuery(@NotBlank String mapCode, @NotEmpty List<String> from, @NotEmpty List<String> to) {
    }
}
