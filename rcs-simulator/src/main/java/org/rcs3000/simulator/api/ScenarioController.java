package org.rcs3000.simulator.api;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.error.BizException;
import org.rcs3000.common.error.CommonErrorCode;
import org.rcs3000.simulator.scenario.Scenario;
import org.rcs3000.simulator.scenario.ScenarioRun;
import org.rcs3000.simulator.scenario.ScenarioRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Scenario runs (SIM-12): post a scenario as YAML (or JSON, which is YAML too). */
@RestController
@RequestMapping("/api/simulator/v1/scenarios")
public class ScenarioController {

    private final ScenarioRunner runner;
    private final JsonMapper json;

    public ScenarioController(ScenarioRunner runner, JsonMapper json) {
        this.runner = runner;
        this.json = json;
    }

    @PostMapping(consumes = {"application/yaml", "application/x-yaml", "text/yaml", "text/plain", "application/json"})
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ScenarioRun.Report> start(@RequestBody String body) {
        Scenario scenario;
        try {
            scenario = Scenario.parse(body, json);
        } catch (IllegalArgumentException e) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, e.getMessage());
        }
        try {
            return ApiResponse.ok(runner.start(scenario));
        } catch (IllegalStateException e) {
            throw new BizException(CommonErrorCode.CONFLICT, e.getMessage());
        }
    }

    /** The latest run; {@code data} is null before the first one. */
    @GetMapping("/current")
    public ApiResponse<ScenarioRun.Report> current() {
        return ApiResponse.ok(runner.current().orElse(null));
    }

    /** Stops sending orders; the ones already sent go on and are still counted. */
    @DeleteMapping("/current")
    public ApiResponse<ScenarioRun.Report> stop() {
        return ApiResponse.ok(runner.stop().orElseThrow(() ->
                new BizException(CommonErrorCode.NOT_FOUND, "no scenario has run yet")));
    }
}
