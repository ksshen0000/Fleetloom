package org.fleetloom.simulator.app;

import java.util.Optional;

/**
 * Top speed of a vehicle by its registered type, so that a simulated vehicle drives like the type
 * it is registered as. Used for vehicles that do not set a speed themselves.
 */
public interface VehicleSpeeds {

    /** @return m/s, empty if the vehicle is not registered */
    Optional<Double> maxSpeed(String manufacturer, String serialNumber);
}
