package org.fleetloom.resource.carrier.domain;

import java.time.Instant;
import java.util.Set;

/**
 * @param length                 m
 * @param maxLoad                kg
 * @param compatibleVehicleTypes vehicle type codes able to carry it; empty means all
 */
public record CarrierType(Long id, String code, String name, CarrierCategory category, double length, double width,
        double height, double maxLoad, Set<String> compatibleVehicleTypes, Instant createdAt, Instant updatedAt) {

    public CarrierType {
        compatibleVehicleTypes = Set.copyOf(compatibleVehicleTypes);
    }

    public boolean carriableBy(String vehicleType) {
        return compatibleVehicleTypes.isEmpty() || compatibleVehicleTypes.contains(vehicleType);
    }
}
