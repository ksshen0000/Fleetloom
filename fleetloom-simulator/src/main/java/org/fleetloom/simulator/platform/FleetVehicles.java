package org.fleetloom.simulator.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.simulator.app.KnownVehicles;
import org.fleetloom.simulator.app.SimulatorProperties;
import org.fleetloom.simulator.core.SiteMap;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * The simulated vehicles Fleetloom fleet control still knows, where it last saw them: vehicles added at
 * run time survive a restart of the simulator. Only vehicles on the simulator's map are restored.
 */
final class FleetVehicles implements KnownVehicles {

    private final RestClient fleet;
    private final double nodeTolerance;

    FleetVehicles(RestClient fleet, double nodeTolerance) {
        this.fleet = fleet;
        this.nodeTolerance = nodeTolerance;
    }

    @Override
    public List<SimulatorProperties.VehicleConfig> find(String manufacturer, Optional<SiteMap> map) {
        if (map.isEmpty()) {
            return List.of();
        }
        SiteMap siteMap = map.get();
        ApiResponse<List<VehicleStatusDto>> response = fleet.get().uri("/api/fleet/v1/vehicles").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        List<VehicleStatusDto> known = response == null || response.data() == null ? List.of() : response.data();
        List<SimulatorProperties.VehicleConfig> result = new ArrayList<>();
        for (VehicleStatusDto v : known) {
            if (!manufacturer.equals(v.manufacturer()) || !v.positionInitialized()
                    || !siteMap.mapId().equals(v.mapCode())) {
                continue;
            }
            // on its last point if it stands there, else where it was
            String point = siteMap.point(v.lastNodeId())
                    .filter(p -> Math.hypot(p.x() - v.x(), p.y() - v.y()) <= nodeTolerance)
                    .map(p -> v.lastNodeId()).orElse(null);
            result.add(new SimulatorProperties.VehicleConfig(v.serialNumber(), point, v.x(), v.y(), v.theta(),
                    v.battery()));
        }
        return result;
    }
}
