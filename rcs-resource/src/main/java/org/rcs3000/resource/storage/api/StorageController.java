package org.rcs3000.resource.storage.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.resource.storage.application.StorageService;
import org.rcs3000.resource.storage.application.StorageViews.LocationDefinition;
import org.rcs3000.resource.storage.application.StorageViews.LocationView;
import org.rcs3000.resource.storage.application.StorageViews.ZoneView;
import org.rcs3000.resource.storage.domain.LocationFilter;
import org.rcs3000.resource.storage.domain.StorageLocationStatus;
import org.rcs3000.resource.storage.domain.StorageLocationType;
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
@RequestMapping("/api/resource/v1")
@Validated
public class StorageController {

    static final String CODE = "[A-Za-z0-9_.:-]{1,64}";

    private final StorageService storage;

    public StorageController(StorageService storage) {
        this.storage = storage;
    }

    @GetMapping("/zones")
    public ApiResponse<List<ZoneView>> zones() {
        return ApiResponse.ok(storage.listZones());
    }

    @PostMapping("/zones")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ZoneView> createZone(@Valid @RequestBody ZoneRequest r) {
        return ApiResponse.ok(storage.createZone(r.code(), r.name(), r.type(), r.description()));
    }

    @PutMapping("/zones/{code}")
    public ApiResponse<ZoneView> updateZone(@PathVariable String code, @Valid @RequestBody ZoneRequest r) {
        return ApiResponse.ok(storage.updateZone(code, r.name(), r.type(), r.description()));
    }

    @DeleteMapping("/zones/{code}")
    public ApiResponse<Void> deleteZone(@PathVariable String code) {
        storage.deleteZone(code);
        return ApiResponse.ok();
    }

    @GetMapping("/storage-locations")
    public ApiResponse<List<LocationView>> locations(@RequestParam(required = false) String zone,
            @RequestParam(required = false) StorageLocationStatus status, @RequestParam(required = false) String map) {
        return ApiResponse.ok(storage.listLocations(new LocationFilter(zone, status, map)));
    }

    @GetMapping("/storage-locations/available")
    public ApiResponse<List<LocationView>> available(@RequestParam(required = false) String zone,
            @RequestParam(required = false) String carrierType, @RequestParam(required = false) String map,
            @RequestParam(required = false) String nearPoint,
            @RequestParam(defaultValue = "10") @Min(1) @Max(500) int limit) {
        return ApiResponse.ok(storage.findAvailable(zone, carrierType, map, nearPoint, limit));
    }

    @GetMapping("/storage-locations/{code}")
    public ApiResponse<LocationView> location(@PathVariable String code) {
        return ApiResponse.ok(storage.getLocation(code));
    }

    @PostMapping("/storage-locations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LocationView> createLocation(@Valid @RequestBody LocationRequest r) {
        return ApiResponse.ok(storage.createLocation(r.toDefinition()));
    }

    /** Creates several locations, all or none (STO-03). */
    @PostMapping("/storage-locations/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<LocationView>> createLocations(@NotEmpty @RequestBody List<@Valid LocationRequest> requests) {
        return ApiResponse.ok(storage.createLocations(requests.stream().map(LocationRequest::toDefinition).toList()));
    }

    @PutMapping("/storage-locations/{code}")
    public ApiResponse<LocationView> updateLocation(@PathVariable String code, @Valid @RequestBody LocationRequest r) {
        return ApiResponse.ok(storage.updateLocation(code, r.toDefinition()));
    }

    @DeleteMapping("/storage-locations/{code}")
    public ApiResponse<Void> deleteLocation(@PathVariable String code) {
        storage.deleteLocation(code);
        return ApiResponse.ok();
    }

    @PostMapping("/storage-locations/{code}/disable")
    public ApiResponse<LocationView> disable(@PathVariable String code) {
        return ApiResponse.ok(storage.disable(code));
    }

    @PostMapping("/storage-locations/{code}/enable")
    public ApiResponse<LocationView> enable(@PathVariable String code) {
        return ApiResponse.ok(storage.enable(code));
    }

    public record ZoneRequest(@NotBlank @Pattern(regexp = CODE) String code, @NotBlank String name,
            @NotBlank String type, String description) {
    }

    /** {@code code} is ignored on update; {@code level} and {@code height} default to 0. */
    public record LocationRequest(
            @NotBlank @Pattern(regexp = CODE) String code,
            @NotBlank String zoneCode,
            @NotNull StorageLocationType type,
            @NotBlank String mapCode,
            @NotBlank String accessPoint,
            Double accessTheta,
            @PositiveOrZero Integer level,
            @PositiveOrZero Double height,
            Set<String> allowedCarrierTypes) {

        LocationDefinition toDefinition() {
            return new LocationDefinition(code, zoneCode, type, mapCode, accessPoint, accessTheta,
                    level == null ? 0 : level, height == null ? 0 : height, allowedCarrierTypes);
        }
    }
}
