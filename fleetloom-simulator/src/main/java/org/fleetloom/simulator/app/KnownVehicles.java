package org.fleetloom.simulator.app;

import java.util.List;
import java.util.Optional;

import org.fleetloom.simulator.core.SiteMap;

/**
 * Vehicles to start besides the configured ones, e.g. those added at run time before a restart,
 * which a fleet manager still knows: they come back where they were, like real ones after a reboot.
 */
public interface KnownVehicles {

    /** @param map the simulator's map, if it has one */
    List<SimulatorProperties.VehicleConfig> find(String manufacturer, Optional<SiteMap> map);
}
