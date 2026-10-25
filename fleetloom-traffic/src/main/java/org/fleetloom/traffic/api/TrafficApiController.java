package org.fleetloom.traffic.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.error.CommonErrorCode;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.traffic.CostMatrixDto;
import org.fleetloom.contracts.traffic.CostQueryDto;
import org.fleetloom.contracts.traffic.ReservationDto;
import org.fleetloom.contracts.traffic.RouteViewDto;
import org.fleetloom.traffic.app.TrafficEngine;
import org.fleetloom.traffic.app.TrafficTuning;
import org.fleetloom.traffic.core.TrafficErrorCode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/traffic/v1")
public class TrafficApiController {

    private final TrafficEngine engine;
    private final TrafficTuning tuning;

    public TrafficApiController(TrafficEngine engine, TrafficTuning tuning) {
        this.engine = engine;
        this.tuning = tuning;
    }

    @GetMapping("/settings")
    public ApiResponse<TrafficTuning.Tunables> settings() {
        return ApiResponse.ok(tuning.get());
    }

    /** Changes the reservation and detour parameters (WEB-09). */
    @PutMapping("/settings")
    public ApiResponse<TrafficTuning.Tunables> updateSettings(@Valid @RequestBody Settings r) {
        return ApiResponse.ok(tuning.update(new TrafficTuning.Tunables(r.lookaheadNodes(), r.lookaheadDistance(),
                r.occupiedPenalty(), r.blockedEventAfterSeconds(), r.replanAfterSeconds())));
    }

    public record Settings(@NotNull @Min(1) @Max(20) Integer lookaheadNodes,
            @NotNull @DecimalMin("0") @DecimalMax("100") Double lookaheadDistance,
            @NotNull @DecimalMin("0") @DecimalMax("3600") Double occupiedPenalty,
            @NotNull @Min(1) @Max(3600) Long blockedEventAfterSeconds,
            @NotNull @Min(1) @Max(3600) Long replanAfterSeconds) {
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
        if (query.vehicles() != null && query.vehicles().size() != query.from().size()) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "give one vehicle per from point, or none");
        }
        return ApiResponse.ok(engine.call(c -> c.costs(
                new CostQueryDto(query.mapCode(), query.from(), query.to(), query.vehicles()))));
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

    /** @param vehicles optional, the vehicle standing at each {@code from} point */
    public record CostQuery(@NotBlank String mapCode, @NotEmpty List<String> from, @NotEmpty List<String> to,
            List<String> vehicles) {
    }
}
