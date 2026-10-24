package org.fleetloom.fleet.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.fleet.app.FleetService;
import org.fleetloom.fleet.core.MessageLog;
import org.fleetloom.vda5050.v2.factsheet.Factsheet;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vehicle status and commands. The order endpoints are internal: route commands come from the
 * traffic service, which plans and reserves the route first (requirement FLT-08); they are not to be
 * exposed through the gateway.
 */
@RestController
@Validated
@RequestMapping("/api/fleet/v1/vehicles")
public class FleetController {

    private final FleetService fleet;
    private final MessageLog messages;

    public FleetController(FleetService fleet, MessageLog messages) {
        this.fleet = fleet;
        this.messages = messages;
    }

    /** Messages exchanged with the vehicle, newest first (FLT-07). */
    @GetMapping("/{code}/messages")
    public ApiResponse<List<MessageLog.Entry>> messages(@PathVariable String code,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") @Min(1) @Max(2000) int limit) {
        return ApiResponse.ok(messages.find(code, from, to, limit));
    }

    @GetMapping
    public ApiResponse<List<VehicleStatusDto>> vehicles() {
        return ApiResponse.ok(fleet.vehicles());
    }

    @GetMapping("/{code}")
    public ApiResponse<VehicleStatusDto> vehicle(@PathVariable String code) {
        return ApiResponse.ok(fleet.vehicle(code));
    }

    /** Removes a vehicle that is offline or lost from the list. */
    @DeleteMapping("/{code}")
    public ApiResponse<Void> forget(@PathVariable String code) {
        fleet.forget(code);
        return ApiResponse.ok();
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

    @PostMapping("/{code}/stop-charging")
    public ApiResponse<String> stopCharging(@PathVariable String code) {
        return ApiResponse.ok(fleet.stopCharging(code));
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
