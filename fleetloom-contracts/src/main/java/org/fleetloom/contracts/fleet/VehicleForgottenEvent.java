package org.fleetloom.contracts.fleet;

import java.time.Instant;

/** An offline vehicle was removed from fleet control, e.g. a deleted simulated vehicle. */
public record VehicleForgottenEvent(String vehicleCode, Instant at) {

    public static final String TYPE = "VehicleForgotten";
}
