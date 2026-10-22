package org.rcs3000.dispatch.api;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.dispatch.CreateOrderDto;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderType;
import org.rcs3000.contracts.dispatch.OrderViewDto;
import org.rcs3000.dispatch.app.DispatchEngine;
import org.rcs3000.dispatch.app.DispatchTuning;
import org.rcs3000.dispatch.core.DispatchErrorCode;
import org.rcs3000.dispatch.core.Dispatcher;
import org.rcs3000.dispatch.core.OrderTemplate;
import org.rcs3000.dispatch.core.Ports;
import org.rcs3000.dispatch.infra.OrderStatistics;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch/v1")
@Validated
public class DispatchController {

    private final DispatchEngine engine;
    private final OrderStatistics statistics;
    private final Ports.TemplateRepository templates;
    private final DispatchTuning tuning;

    public DispatchController(DispatchEngine engine, OrderStatistics statistics, Ports.TemplateRepository templates,
            DispatchTuning tuning) {
        this.engine = engine;
        this.statistics = statistics;
        this.templates = templates;
        this.tuning = tuning;
    }

    // ------------------------------------------------------------------ parameters (WEB-09)

    @GetMapping("/settings")
    public ApiResponse<DispatchTuning.Tunables> settings() {
        return ApiResponse.ok(tuning.get());
    }

    @PutMapping("/settings")
    public ApiResponse<DispatchTuning.Tunables> updateSettings(@Valid @RequestBody Settings request) {
        return ApiResponse.ok(tuning.update(new DispatchTuning.Tunables(request.defaultPriority(),
                request.parkAfterIdleSeconds())));
    }

    public record Settings(@NotNull @Min(1) @Max(9) Integer defaultPriority,
            @NotNull @Min(0) @Max(86_400) Long parkAfterIdleSeconds) {
    }

    // ------------------------------------------------------------------ order templates (DSP-03)

    @GetMapping("/templates")
    public ApiResponse<List<OrderTemplate>> templates() {
        return ApiResponse.ok(templates.findAll());
    }

    /** Creates or replaces a template; orders already created keep the legs they were given. */
    @PutMapping("/templates/{code}")
    public ApiResponse<OrderTemplate> saveTemplate(@PathVariable @Pattern(regexp = "[A-Z0-9_-]{1,32}") String code,
            @Valid @RequestBody Template request) {
        OrderTemplate template = new OrderTemplate(code, request.name(), request.pickAction(), request.dropAction(),
                request.viaPoints(), request.viaAction(), request.description());
        templates.save(template);
        return ApiResponse.ok(templates.find(code).orElse(template));
    }

    @DeleteMapping("/templates/{code}")
    public ApiResponse<Void> deleteTemplate(@PathVariable String code) {
        if (OrderTemplate.STANDARD.equals(code)) {
            throw new BizException(DispatchErrorCode.TEMPLATE_PROTECTED, "the standard template cannot be deleted");
        }
        if (!templates.delete(code)) {
            throw new BizException(DispatchErrorCode.TEMPLATE_NOT_FOUND, "order template %s not found".formatted(code));
        }
        return ApiResponse.ok();
    }

    public record Template(@NotBlank @Size(max = 64) String name, @Size(max = 64) String pickAction,
            @Size(max = 64) String dropAction, List<@NotBlank String> viaPoints, @Size(max = 64) String viaAction,
            @Size(max = 256) String description) {
    }

    /** Throughput and timing per hour for the last {@code hours} hours, current hour included (DSP-12). */
    @GetMapping("/statistics")
    public ApiResponse<OrderStatistics.Report> statistics(@RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.ok(statistics.lastHours(hours));
    }

    /** Creates a transport or move order; repeated external numbers return the existing order (DSP-01). */
    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrderViewDto> create(@Valid @RequestBody CreateOrder request) {
        return ApiResponse.ok(engine.call(d -> d.create(request.toDto())));
    }

    @GetMapping("/orders")
    public ApiResponse<List<OrderViewDto>> orders(@RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return ApiResponse.ok(engine.call(d -> d.list(status, limit)));
    }

    @GetMapping("/orders/{orderNo}")
    public ApiResponse<OrderViewDto> order(@PathVariable String orderNo) {
        return ApiResponse.ok(engine.call(d -> d.get(orderNo)));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    public ApiResponse<OrderViewDto> cancel(@PathVariable String orderNo) {
        return ApiResponse.ok(engine.call(d -> d.cancel(orderNo)));
    }

    @PostMapping("/orders/{orderNo}/suspend")
    public ApiResponse<OrderViewDto> suspend(@PathVariable String orderNo) {
        return ApiResponse.ok(engine.call(d -> d.suspend(orderNo)));
    }

    @PostMapping("/orders/{orderNo}/resume")
    public ApiResponse<OrderViewDto> resume(@PathVariable String orderNo) {
        return ApiResponse.ok(engine.call(d -> d.resume(orderNo)));
    }

    @GetMapping("/vehicles")
    public ApiResponse<List<Dispatcher.VehicleView>> vehicles() {
        return ApiResponse.ok(engine.call(Dispatcher::vehicles));
    }

    /** Switches a vehicle between accepting and not accepting orders (VEH-04). */
    @PutMapping("/vehicles/{code}/accepting")
    public ApiResponse<Void> accepting(@PathVariable String code, @Valid @RequestBody Accepting request) {
        engine.call(d -> {
            d.setAccepting(code, request.accepting());
            return null;
        });
        return ApiResponse.ok();
    }

    public record CreateOrder(String externalNo, @NotNull OrderType type, @Min(1) @Max(9) Integer priority,
            String carrierCode, String targetLocation, String targetZone, String mapCode, String targetPoint,
            String vehicleCode, Instant deadline, String template) {

        CreateOrderDto toDto() {
            return new CreateOrderDto(externalNo, type, priority, carrierCode, targetLocation, targetZone, mapCode,
                    targetPoint, vehicleCode, deadline, template);
        }
    }

    public record Accepting(@NotNull Boolean accepting) {
    }
}
