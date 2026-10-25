package org.fleetloom.simulator.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.error.CommonErrorCode;
import org.fleetloom.simulator.app.SimulatorProperties;
import org.fleetloom.simulator.app.SimulatorRunner;
import org.fleetloom.simulator.core.Pose;
import org.fleetloom.simulator.core.SimulatedAgv;
import org.fleetloom.simulator.core.SiteMap;
import org.fleetloom.simulator.core.VirtualVehicle;
import org.fleetloom.vda5050.v2.ErrorLevel;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
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
@RequestMapping("/api/simulator/v1")
@Validated
public class SimulatorController {

    private final SimulatorRunner runner;

    public SimulatorController(SimulatorRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/vehicles")
    public ApiResponse<List<VehicleView>> vehicles() {
        return ApiResponse.ok(runner.engine().query(e -> e.agvs().stream().map(VehicleView::of).toList()));
    }

    /**
     * Creates virtual vehicles (SIM-01, SIM-11). They exist until removed or until the simulator
     * restarts; vehicles that should always be there belong in the configuration.
     */
    @PostMapping("/vehicles")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<VehicleView>> addVehicles(@RequestBody @NotEmpty @Size(max = 100) List<@Valid NewVehicle> request) {
        List<SimulatedAgv> added = new ArrayList<>();
        for (NewVehicle v : request) {
            try {
                added.add(runner.addVehicle(new SimulatorProperties.VehicleConfig(v.serialNumber(), v.initialPoint(),
                        v.x(), v.y(), v.theta() == null ? 0 : v.theta(), v.battery(), v.maxSpeed())));
            } catch (IllegalArgumentException e) {
                throw new BizException(CommonErrorCode.BAD_REQUEST, e.getMessage());
            }
        }
        return ApiResponse.ok(runner.engine().query(e -> added.stream().map(VehicleView::of).toList()));
    }

    @DeleteMapping("/vehicles/{serialNumber}")
    public ApiResponse<Void> removeVehicle(@PathVariable String serialNumber) {
        if (!runner.removeVehicle(serialNumber)) {
            throw new BizException(CommonErrorCode.NOT_FOUND, "no simulated vehicle " + serialNumber);
        }
        return ApiResponse.ok();
    }

    /**
     * Points of the simulator's map where new vehicles can start, best first: parking points, then
     * points on a through road, then dead ends (often docks and storage bays). Chargers are left out,
     * the vehicles would block them.
     */
    @GetMapping("/map/points")
    public ApiResponse<List<String>> mapPoints() {
        return ApiResponse.ok(runner.siteMap().map(SimulatorController::startPoints).orElse(List.of()));
    }

    static List<String> startPoints(SiteMap m) {
        Map<String, Integer> degree = new HashMap<>();
        m.paths().values().forEach(p -> {
            degree.merge(p.fromPoint(), 1, Integer::sum);
            degree.merge(p.toPoint(), 1, Integer::sum);
        });
        Comparator<String> rank = Comparator.comparingInt(code ->
                "PARKING".equals(m.points().get(code).type()) ? 0 : degree.getOrDefault(code, 0) >= 2 ? 1 : 2);
        return m.points().keySet().stream().filter(code -> !m.isChargingPoint(code))
                .sorted(rank.thenComparing(Comparator.naturalOrder())).toList();
    }

    /**
     * @param initialPoint a point of the simulator's map; or {@code x}/{@code y}
     * @param battery      percent, full when absent
     * @param maxSpeed     m/s; when absent that of the vehicle's registered type
     */
    public record NewVehicle(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.:-]{1,64}") String serialNumber,
            String initialPoint, Double x, Double y, Double theta, @DecimalMin("0") @DecimalMax("100") Double battery,
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("10") Double maxSpeed) {
    }

    @PostMapping("/map/reload")
    public ApiResponse<String> reloadMap() {
        SiteMap map = runner.reloadMap();
        return ApiResponse.ok(map.mapId() + " v" + map.version());
    }

    @PutMapping("/time-scale")
    public ApiResponse<Double> timeScale(@RequestBody TimeScale request) {
        runner.engine().setTimeScale(request.value());
        return ApiResponse.ok(runner.engine().timeScale());
    }

    public record TimeScale(@Positive double value) {
    }

    // ------------------------------------------------------------------ fault injection (SIM-09)

    /** Makes the vehicle report an error; FATAL also stops it. {@code seconds} absent or 0: until cleared. */
    @PostMapping("/vehicles/{serialNumber}/faults/error")
    public ApiResponse<VehicleView> injectError(@PathVariable String serialNumber, @Valid @RequestBody InjectError request) {
        return onVehicle(serialNumber, a -> a.vehicle().injectError(request.errorType(),
                "FATAL".equals(request.level()) ? ErrorLevel.FATAL : ErrorLevel.WARNING,
                request.description(), request.seconds() == null ? 0 : request.seconds()));
    }

    /** Stops the vehicle as if something stood in its way. */
    @PostMapping("/vehicles/{serialNumber}/faults/obstacle")
    public ApiResponse<VehicleView> obstacle(@PathVariable String serialNumber, @Valid @RequestBody Duration request) {
        return onVehicle(serialNumber, a -> a.vehicle().blockByObstacle(request.seconds()));
    }

    /** Cuts the vehicle off the network: no state goes out, no orders come in. */
    @PostMapping("/vehicles/{serialNumber}/faults/disconnect")
    public ApiResponse<VehicleView> disconnect(@PathVariable String serialNumber, @Valid @RequestBody Duration request) {
        return onVehicle(serialNumber, a -> a.dropConnection(request.seconds()));
    }

    @DeleteMapping("/vehicles/{serialNumber}/faults")
    public ApiResponse<VehicleView> clearFaults(@PathVariable String serialNumber) {
        return onVehicle(serialNumber, a -> {
            a.vehicle().clearFaults();
            a.restoreConnection();
        });
    }

    private ApiResponse<VehicleView> onVehicle(String serialNumber, Consumer<SimulatedAgv> action) {
        SimulatedAgv agv = runner.engine().query(e -> e.find(serialNumber)).orElseThrow(() ->
                new BizException(CommonErrorCode.NOT_FOUND, "no simulated vehicle " + serialNumber));
        // vehicles are only touched on the simulation thread
        return ApiResponse.ok(runner.engine().query(e -> {
            action.accept(agv);
            return VehicleView.of(agv);
        }));
    }

    /** @param level WARNING (default) or FATAL */
    public record InjectError(@NotBlank String errorType, @Pattern(regexp = "WARNING|FATAL") String level,
            String description, @PositiveOrZero Double seconds) {
    }

    public record Duration(@Positive double seconds) {
    }

    /** @param faults injected errors still reported; {@code connectionDropped} while a simulated outage lasts */
    public record VehicleView(String serialNumber, double x, double y, double theta, double velocity, boolean driving,
            String orderId, String lastNodeId, double battery, boolean charging, List<String> faults,
            boolean connectionDropped) {

        static VehicleView of(SimulatedAgv agv) {
            VirtualVehicle v = agv.vehicle();
            Pose p = v.pose();
            return new VehicleView(v.serialNumber(), p.x(), p.y(), p.theta(), v.velocity(), v.isDriving(), v.orderId(),
                    v.lastNodeId(), Math.round(v.battery() * 10) / 10.0, v.isCharging(), v.faults(),
                    agv.isConnectionDropped());
        }
    }
}
