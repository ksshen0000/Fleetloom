package org.rcs3000.contracts.dispatch;

import java.time.Instant;

/**
 * A transport request from the console or an upstream system (requirement DSP-01).
 *
 * @param externalNo     upstream order number; a second order with the same number returns the first one
 * @param priority       1 (lowest) to 9 (highest); default 5
 * @param carrierCode    TRANSPORT: the carrier to move; its current location is the source
 * @param targetLocation TRANSPORT: target storage location, or empty to pick one in {@code targetZone}
 * @param targetZone     TRANSPORT: zone to choose an empty location from, nearest to the source
 * @param mapCode        MOVE: map of {@code targetPoint}
 * @param targetPoint    MOVE: point to send the vehicle to
 * @param vehicleCode    optional: use exactly this vehicle
 * @param deadline       optional: when the order should be finished; an alarm is raised if it is not (DSP-10)
 * @param template       TRANSPORT, optional: order template code (DSP-03); the standard pick / drop if absent
 */
public record CreateOrderDto(String externalNo, OrderType type, Integer priority, String carrierCode,
        String targetLocation, String targetZone, String mapCode, String targetPoint, String vehicleCode,
        Instant deadline, String template) {

    public CreateOrderDto(String externalNo, OrderType type, Integer priority, String carrierCode,
            String targetLocation, String targetZone, String mapCode, String targetPoint, String vehicleCode) {
        this(externalNo, type, priority, carrierCode, targetLocation, targetZone, mapCode, targetPoint, vehicleCode, null,
                null);
    }

    public CreateOrderDto(String externalNo, OrderType type, Integer priority, String carrierCode,
            String targetLocation, String targetZone, String mapCode, String targetPoint, String vehicleCode,
            Instant deadline) {
        this(externalNo, type, priority, carrierCode, targetLocation, targetZone, mapCode, targetPoint, vehicleCode,
                deadline, null);
    }
}
