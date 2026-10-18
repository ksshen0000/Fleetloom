package org.rcs3000.contracts.fleet;

/** Identifies what was sent to a vehicle; the vehicle's state reports the same ids once it accepted it. */
public record OrderRefDto(String vehicleCode, String orderId, long orderUpdateId) {
}
