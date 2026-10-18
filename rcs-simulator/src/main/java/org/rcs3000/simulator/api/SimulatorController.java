package org.rcs3000.simulator.api;

import java.util.List;

import jakarta.validation.constraints.Positive;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.simulator.app.SimulatorRunner;
import org.rcs3000.simulator.core.Pose;
import org.rcs3000.simulator.core.SiteMap;
import org.rcs3000.simulator.core.VirtualVehicle;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
        return ApiResponse.ok(runner.engine().query(e -> e.agvs().stream().map(a -> VehicleView.of(a.vehicle())).toList()));
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

    public record VehicleView(String serialNumber, double x, double y, double theta, double velocity, boolean driving,
            String orderId, String lastNodeId, double battery, boolean charging) {

        static VehicleView of(VirtualVehicle v) {
            Pose p = v.pose();
            return new VehicleView(v.serialNumber(), p.x(), p.y(), p.theta(), v.velocity(), v.isDriving(), v.orderId(),
                    v.lastNodeId(), Math.round(v.battery() * 10) / 10.0, v.isCharging());
        }
    }
}
