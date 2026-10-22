package org.rcs3000.resource.vehicle.api;

import java.time.Instant;
import java.util.List;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.resource.vehicle.application.VehicleHistory;
import org.rcs3000.resource.vehicle.domain.VehicleSample;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/resource/v1")
public class VehicleHistoryController {

    private final VehicleHistory history;

    public VehicleHistoryController(VehicleHistory history) {
        this.history = history;
    }

    /** Position and battery samples, oldest first; the last hour unless a range is given (VEH-09). */
    @GetMapping("/vehicles/{code}/history")
    public ApiResponse<List<VehicleSample>> history(@PathVariable String code,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "5000") int limit) {
        return ApiResponse.ok(history.find(code, from, to, limit));
    }
}
