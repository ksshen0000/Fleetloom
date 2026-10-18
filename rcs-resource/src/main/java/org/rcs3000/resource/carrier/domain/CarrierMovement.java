package org.rcs3000.resource.carrier.domain;

import java.time.Instant;

/** @param orderRef transport order that caused the move, or {@code null} for manual corrections */
public record CarrierMovement(String carrierCode, CarrierLocationType fromType, String fromRef,
        CarrierLocationType toType, String toRef, String orderRef, Instant movedAt) {
}
