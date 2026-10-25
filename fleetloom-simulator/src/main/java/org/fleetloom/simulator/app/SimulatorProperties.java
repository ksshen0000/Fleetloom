package org.fleetloom.simulator.app;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fleetloom.simulator.core.VehicleProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param tickMillis            real-time period of one simulation step
 * @param timeScale             simulated seconds per real second
 * @param stateInterval         seconds between periodic state messages
 * @param visualizationInterval seconds between visualization messages
 * @see org.fleetloom.simulator.platform.PlatformProperties what is only used together with Fleetloom
 */
@ConfigurationProperties("fleetloom.simulator")
public record SimulatorProperties(
        @DefaultValue Mqtt mqtt,
        @DefaultValue("FLEETLOOM-SIM") String manufacturer,
        @DefaultValue("50") long tickMillis,
        @DefaultValue("1.0") double timeScale,
        @DefaultValue("1.0") double stateInterval,
        @DefaultValue("0.1") double visualizationInterval,
        @DefaultValue MapSettings map,
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
     * Where the vehicles learn the site layout from; with neither, orders must carry node positions.
     *
     * @param file JSON file with {@code points} and {@code paths}, see {@link FileMapSource}
     * @param code the map's id: the map to load from Fleetloom when there is no file, the id reported
     *             in the vehicle state otherwise (the file name when empty)
     */
    public record MapSettings(@DefaultValue("") String file, @DefaultValue("") String code) {
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
                    actionDurations == null ? Map.of("pick", 2.0, "drop", 2.0, "lift", 2.0, "lower", 2.0) : actionDurations,
                    // jacking actions of the LIFT order template
                    customActionTypes == null ? Set.of("lift", "lower") : customActionTypes);
        }
    }

    /**
     * Where a vehicle starts: on {@code initialPoint} of the map, or at {@code x}/{@code y}.
     *
     * @param battery  initial charge in percent, full when absent
     * @param maxSpeed m/s; when absent that of the vehicle's registered type, else {@code profile.maxSpeed}
     */
    public record VehicleConfig(String serialNumber, String initialPoint, Double x, Double y,
            @DefaultValue("0") double theta, Double battery, Double maxSpeed) {
    }
}
