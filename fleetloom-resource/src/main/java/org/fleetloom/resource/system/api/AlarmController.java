package org.fleetloom.resource.system.api;

import java.util.List;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.resource.system.application.AlarmService;
import org.fleetloom.resource.system.domain.Alarm;
import org.fleetloom.resource.system.domain.AlarmLevel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Alarm list and handling (WEB-08). Changes are pushed to browsers as {@code AlarmChanged} events. */
@RestController
@RequestMapping("/api/resource/v1/alarms")
public class AlarmController {

    private final AlarmService alarms;

    public AlarmController(AlarmService alarms) {
        this.alarms = alarms;
    }

    /** @param open {@code true} for open alarms, {@code false} for cleared ones, absent for both */
    @GetMapping
    public ApiResponse<List<Alarm>> list(@RequestParam(required = false) Boolean open,
            @RequestParam(required = false) AlarmLevel level, @RequestParam(required = false) String vehicle,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(alarms.list(open, level, vehicle, limit));
    }

    @GetMapping("/summary")
    public ApiResponse<AlarmService.Summary> summary() {
        return ApiResponse.ok(alarms.summary());
    }

    @PostMapping("/{id}/acknowledge")
    public ApiResponse<Alarm> acknowledge(@PathVariable long id,
            @RequestHeader(value = AuthController.USER_HEADER, required = false) String user) {
        return ApiResponse.ok(alarms.acknowledge(id, user));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Alarm> close(@PathVariable long id,
            @RequestHeader(value = AuthController.USER_HEADER, required = false) String user) {
        return ApiResponse.ok(alarms.close(id, user));
    }
}
