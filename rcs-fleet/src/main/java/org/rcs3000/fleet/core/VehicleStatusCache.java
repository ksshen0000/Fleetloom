package org.rcs3000.fleet.core;

import org.rcs3000.contracts.fleet.VehicleStatusDto;

/** Outbound port: latest status of each vehicle, readable by other services (requirement 7.1). */
public interface VehicleStatusCache {

    void put(VehicleStatusDto status);

    default void remove(String code) {
    }

    VehicleStatusCache NONE = status -> { };
}
