package org.rcs3000.contracts.storage;

/** Reports that the carrier has been set down in its locked target location. */
public record DroppedOffDto(String owner, String carrierCode, String targetLocation) {
}
