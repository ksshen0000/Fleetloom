package org.rcs3000.fleet.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.fleet.app.FleetService;
import org.rcs3000.vda5050.v2.factsheet.Factsheet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vehicle status and commands. The order endpoints are internal: route commands come from the
 * traffic service, which plans and reserves the route first (requirement FLT-08); they are not to be
 * exposed through the gateway.
 */
@RestController
@RequestMapping("/api/fleet/v1/vehicles")
public class FleetController {

    private final FleetService fleet;

    public FleetController(FleetService fleet) {
        this.fleet = fleet;
    }

    @GetMapping
    public ApiResponse<List<VehicleStatusDto>> vehicles() {
        return ApiResponse.ok(fleet.vehicles());
    }

    @GetMapping("/{code}")
    public ApiResponse<VehicleStatusDto> vehicle(@PathVariable String code) {
        return ApiResponse.ok(fleet.vehicle(code));
    }

    @GetMapping("/{code}/factsheet")
    public ApiResponse<Factsheet> factsheet(@PathVariable String code) {
        return ApiResponse.ok(fleet.factsheet(code));
    }

    @PostMapping("/{code}/orders")
    public ApiResponse<OrderRefDto> sendRoute(@PathVariable String code, @RequestBody RouteCommandDto route) {
        return ApiResponse.ok(fleet.sendRoute(code, route));
    }

    @PostMapping("/{code}/orders/current/updates")
    public ApiResponse<OrderRefDto> extendRoute(@PathVariable String code, @RequestBody RouteCommandDto route) {
        return ApiResponse.ok(fleet.extendRoute(code, route));
    }

    @PostMapping("/{code}/pause")
    public ApiResponse<String> pause(@PathVariable String code) {
        return ApiResponse.ok(fleet.pause(code));
    }

    @PostMapping("/{code}/resume")
    public ApiResponse<String> resume(@PathVariable String code) {
        return ApiResponse.ok(fleet.resume(code));
    }

    @PostMapping("/{code}/cancel-order")
    public ApiResponse<String> cancelOrder(@PathVariable String code) {
        return ApiResponse.ok(fleet.cancelOrder(code));
    }

    @PostMapping("/{code}/factsheet-request")
    public ApiResponse<String> requestFactsheet(@PathVariable String code) {
        return ApiResponse.ok(fleet.requestFactsheet(code));
    }

    @PostMapping("/{code}/relocate")
    public ApiResponse<String> relocate(@PathVariable String code, @Valid @RequestBody Relocate request) {
        return ApiResponse.ok(fleet.relocate(code, request.x(), request.y(),
                request.theta() == null ? 0 : request.theta(), request.mapCode(), request.pointCode()));
    }

    /** @param pointCode map point the vehicle stands on, if any */
    public record Relocate(@NotNull Double x, @NotNull Double y, Double theta, String mapCode, String pointCode) {
    }
}
