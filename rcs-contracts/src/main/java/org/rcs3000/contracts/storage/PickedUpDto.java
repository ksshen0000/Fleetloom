package org.rcs3000.contracts.storage;

/** Reports that {@code vehicleCode} has lifted the carrier out of its locked source location. */
public record PickedUpDto(String owner, String carrierCode, String vehicleCode) {
}
