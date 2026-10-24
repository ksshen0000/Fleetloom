package org.fleetloom.resource.vehicle.domain;

import java.time.Instant;

/** Where a vehicle was and how full its battery was at one moment (VEH-09). */
public record VehicleSample(String vehicleCode, Instant at, String mapCode, double x, double y, double theta,
        double battery, boolean charging, boolean driving, String lastNodeId, String connection) {
}
