package org.rcs3000.contracts.storage;

/**
 * Locks the carrier's current location for outbound and {@code targetLocation} for inbound, both or
 * neither.
 *
 * @param owner the transport order; only it may complete or release the locks
 */
public record TransportLockRequestDto(String owner, String carrierCode, String targetLocation) {
}
