package org.rcs3000.resource.vehicle.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.resource.vehicle.application.VehicleRegistryService;
import org.rcs3000.resource.vehicle.domain.Vehicle;
import org.rcs3000.resource.vehicle.domain.VehicleCategory;
import org.rcs3000.resource.vehicle.domain.VehicleType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resource/v1")
public class VehicleRegistryController {

    static final String CODE = "[A-Za-z0-9_.:-]{1,64}";

    private final VehicleRegistryService registry;

    public VehicleRegistryController(VehicleRegistryService registry) {
        this.registry = registry;
    }

    @GetMapping("/vehicle-types")
    public ApiResponse<List<VehicleType>> types() {
        return ApiResponse.ok(registry.listTypes());
    }

    @PostMapping("/vehicle-types")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VehicleType> createType(@Valid @RequestBody TypeRequest r) {
        return ApiResponse.ok(registry.createType(r.code(), r.name(), r.category(), r.length(), r.width(), r.maxSpeed(),
                r.maxLoad(), r.supportedActions()));
    }

    @PutMapping("/vehicle-types/{code}")
    public ApiResponse<VehicleType> updateType(@PathVariable String code, @Valid @RequestBody TypeRequest r) {
        return ApiResponse.ok(registry.updateType(code, r.name(), r.category(), r.length(), r.width(), r.maxSpeed(),
                r.maxLoad(), r.supportedActions()));
    }

    @DeleteMapping("/vehicle-types/{code}")
    public ApiResponse<Void> deleteType(@PathVariable String code) {
        registry.deleteType(code);
        return ApiResponse.ok();
    }

    @GetMapping("/vehicles")
    public ApiResponse<List<Vehicle>> vehicles() {
        return ApiResponse.ok(registry.list());
    }

    @GetMapping("/vehicles/{code}")
    public ApiResponse<Vehicle> vehicle(@PathVariable String code) {
        return ApiResponse.ok(registry.get(code));
    }

    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Vehicle> register(@Valid @RequestBody VehicleRequest r) {
        return ApiResponse.ok(registry.register(r.code(), r.name(), r.typeCode(), r.manufacturer(), r.serialNumber(),
                Boolean.TRUE.equals(r.simulated()), r.initialPoint(), r.low(), r.high()));
    }

    @PutMapping("/vehicles/{code}")
    public ApiResponse<Vehicle> update(@PathVariable String code, @Valid @RequestBody VehicleRequest r) {
        return ApiResponse.ok(registry.update(code, r.name(), r.typeCode(), r.manufacturer(), r.serialNumber(),
                Boolean.TRUE.equals(r.simulated()), r.enabled() == null || r.enabled(), r.initialPoint(), r.low(),
                r.high()));
    }

    @DeleteMapping("/vehicles/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        registry.delete(code);
        return ApiResponse.ok();
    }

    public record TypeRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank String name,
            @NotNull VehicleCategory category, @Positive double length, @Positive double width,
            @Positive double maxSpeed, @Positive double maxLoad, Set<String> supportedActions) {
    }

    /**
     * {@code code} is ignored on update; {@code enabled} only applies to updates. Charge thresholds
     * default to 20 % and 90 %.
     */
    public record VehicleRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank String name,
            @NotBlank String typeCode, @NotBlank String manufacturer,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]+") String serialNumber, Boolean simulated, Boolean enabled,
            String initialPoint, @DecimalMin("0") @DecimalMax("100") Double chargeThresholdLow,
            @DecimalMin("0") @DecimalMax("100") Double chargeThresholdHigh) {

        double low() {
            return chargeThresholdLow == null ? 20 : chargeThresholdLow;
        }

        double high() {
            return chargeThresholdHigh == null ? 90 : chargeThresholdHigh;
        }
    }
}
