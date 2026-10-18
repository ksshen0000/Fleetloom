package org.rcs3000.resource.vehicle.domain;

import java.time.Instant;

import org.rcs3000.common.error.BizException;

/**
 * Registry entry of a vehicle (requirement VEH-02). Its live state is kept by the fleet service, which
 * identifies it by manufacturer and serial number.
 *
 * @param initialPoint        map point the vehicle starts on, e.g. for the simulator
 * @param chargeThresholdLow  battery percent below which an idle vehicle goes charging
 * @param chargeThresholdHigh battery percent from which charging may be interrupted for work
 */
public record Vehicle(Long id, String code, String name, String typeCode, String manufacturer, String serialNumber,
        boolean simulated, boolean enabled, String initialPoint, double chargeThresholdLow, double chargeThresholdHigh,
        Instant createdAt, Instant updatedAt) {

    public Vehicle {
        if (chargeThresholdLow < 0 || chargeThresholdHigh > 100 || chargeThresholdLow >= chargeThresholdHigh) {
            throw new BizException(VehicleErrorCode.INVALID_CHARGE_THRESHOLDS,
                    "charge thresholds must satisfy 0 <= low < high <= 100, got %s / %s"
                            .formatted(chargeThresholdLow, chargeThresholdHigh));
        }
    }
}
