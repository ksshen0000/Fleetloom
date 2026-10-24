package org.fleetloom.contracts.traffic;

/** @param resource {@code NODE:<point>}, {@code PATH:<path>} or {@code AREA:<area>} */
public record ReservationDto(String mapCode, String resource, String vehicleCode) {
}
