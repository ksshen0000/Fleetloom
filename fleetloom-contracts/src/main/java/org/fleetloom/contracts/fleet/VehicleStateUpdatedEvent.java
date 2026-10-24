package org.fleetloom.contracts.fleet;

/** Emitted for every state message a vehicle sends (about once per second, plus on events). */
public record VehicleStateUpdatedEvent(VehicleStatusDto status) {

    public static final String TYPE = "VehicleStateUpdated";
}
