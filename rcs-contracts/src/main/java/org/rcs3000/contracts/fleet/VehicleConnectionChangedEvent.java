package org.rcs3000.contracts.fleet;

import java.time.Instant;

/** @param previous {@code null} when the vehicle was seen for the first time */
public record VehicleConnectionChangedEvent(String vehicleCode, ConnectionStatus previous, ConnectionStatus current,
        String reason, Instant at) {

    public static final String TYPE = "VehicleConnectionChanged";
}
