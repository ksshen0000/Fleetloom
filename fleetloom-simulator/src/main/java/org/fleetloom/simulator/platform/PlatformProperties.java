package org.fleetloom.simulator.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What the simulator needs to work together with Fleetloom. Without it ({@code enabled: false}) it is
 * a plain VDA 5050 simulator for any fleet manager: map from a file, no scenarios.
 *
 * @param resourceUrl     map service ({@code fleetloom.simulator.map.code}) and storage data for scenarios
 * @param dispatchUrl     where scenario runs send their orders (SIM-12)
 * @param fleetUrl        where to look up, at start, the vehicles of an earlier run
 * @param restoreVehicles whether vehicles fleet control still knows from an earlier run (e.g. ones
 *                        added at run time) are started again where they were last seen
 */
@ConfigurationProperties("fleetloom.simulator.platform")
public record PlatformProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("http://localhost:8081") String resourceUrl,
        @DefaultValue("http://localhost:8082") String dispatchUrl,
        @DefaultValue("http://localhost:8083") String fleetUrl,
        @DefaultValue("true") boolean restoreVehicles) {
}
