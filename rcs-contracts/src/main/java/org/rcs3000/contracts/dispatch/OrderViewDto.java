package org.rcs3000.contracts.dispatch;

import java.time.Instant;
import java.util.List;

/**
 * @param legs     one per drive of the vehicle, e.g. to the pick-up and then to the drop-off
 * @param deadline when the order should be finished, if the requester said
 */
public record OrderViewDto(
        String orderNo,
        String externalNo,
        OrderType type,
        int priority,
        OrderStatus status,
        String carrierCode,
        String sourceLocation,
        String targetLocation,
        String mapCode,
        String assignedVehicle,
        int currentLeg,
        List<Leg> legs,
        String reason,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        Instant deadline) {

    /** @param action action at the point, e.g. pick, drop, startCharging; {@code null} for a plain move */
    public record Leg(String point, String action, String status) {
    }
}
