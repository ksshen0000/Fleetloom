package org.rcs3000.simulator.app;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rcs3000.simulator.core.VehicleProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param tickMillis            real-time period of one simulation step
 * @param timeScale             simulated seconds per real second
 * @param stateInterval         seconds between periodic state messages
 * @param visualizationInterval seconds between visualization messages
 */
@ConfigurationProperties("rcs.simulator")
public record SimulatorProperties(
        @DefaultValue Mqtt mqtt,
        @DefaultValue("RCS3000-SIM") String manufacturer,
        @DefaultValue("50") long tickMillis,
        @DefaultValue("1.0") double timeScale,
        @DefaultValue("1.0") double stateInterval,
        @DefaultValue("0.1") double visualizationInterval,
        @DefaultValue MapSource map,
        @DefaultValue Profile profile,
        List<VehicleConfig> vehicles) {

    public SimulatorProperties {
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
    }

    public record Mqtt(
            @DefaultValue("localhost") String host,
            @DefaultValue("1883") int port,
            @DefaultValue("uagv") String interfaceName) {
    }

    /**
     * @param code map to load from the resource service; empty to run without a map (orders must then
     *             carry node positions)
     */
    public record MapSource(
            @DefaultValue("http://localhost:8081") String resourceUrl,
            @DefaultValue("") String code) {
    }

    /** Defaults for every simulated vehicle; see {@link VehicleProfile} for units. */
    public record Profile(
            @DefaultValue("1.5") double maxSpeed,
            @DefaultValue("0.5") double acceleration,
            @DefaultValue("0.5") double deceleration,
            @DefaultValue("0.785") double rotationSpeed,
            @DefaultValue("0.1") double nodeTolerance,
            @DefaultValue("0.005") double drainPerMeter,
            @DefaultValue("0.0005") double drainPerSecond,
            @DefaultValue("0.2") double chargePerSecond,
            @DefaultValue("1.0") double defaultActionDuration,
            Map<String, Double> actionDurations,
            Set<String> customActionTypes) {

        public VehicleProfile toVehicleProfile() {
            return new VehicleProfile(maxSpeed, acceleration, deceleration, rotationSpeed, nodeTolerance,
                    drainPerMeter, drainPerSecond, chargePerSecond, defaultActionDuration,
                    actionDurations == null ? Map.of("pick", 2.0, "drop", 2.0) : actionDurations, customActionTypes);
        }
    }

    /**
     * Where a vehicle starts: on {@code initialPoint} of the map, or at {@code x}/{@code y}.
     */
    public record VehicleConfig(String serialNumber, String initialPoint, Double x, Double y,
            @DefaultValue("0") double theta) {
    }
}
