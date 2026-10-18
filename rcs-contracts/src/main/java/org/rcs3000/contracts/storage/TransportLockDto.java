package org.rcs3000.contracts.storage;

/** Both ends of a locked transport, with the map points vehicles serve them from. */
public record TransportLockDto(String owner, String carrierCode, String carrierType,
        String sourceLocation, String sourceMap, String sourcePoint,
        String targetLocation, String targetMap, String targetPoint) {
}
