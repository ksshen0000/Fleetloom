package org.fleetloom.traffic.core;

import java.util.Map;

/** Outbound port to the vehicle registry: which type each vehicle is, for its speed and allowed paths. */
public interface VehicleRegistry {

    /** No registry: every vehicle plans with the default speed on paths open to all types. */
    VehicleRegistry NONE = Map::of;

    /**
     * The registered vehicles by {@code manufacturer + "/" + serialNumber}.
     *
     * @throws RuntimeException if the registry is unreachable
     */
    Map<String, VehicleKind> vehicles();

    /** @param maxSpeed m/s */
    record VehicleKind(String typeCode, double maxSpeed) {
    }
}
