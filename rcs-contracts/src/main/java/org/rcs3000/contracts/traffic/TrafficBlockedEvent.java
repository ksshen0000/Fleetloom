package org.rcs3000.contracts.traffic;

import java.time.Instant;

/** A vehicle has been waiting for a resource held by another vehicle. */
public record TrafficBlockedEvent(String vehicleCode, String blockedBy, String resource, Instant waitingSince) {

    public static final String TYPE = "TrafficBlocked";
}
