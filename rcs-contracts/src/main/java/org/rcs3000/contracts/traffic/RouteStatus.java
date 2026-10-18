package org.rcs3000.contracts.traffic;

public enum RouteStatus {
    /** Planned and sent, but the vehicle may not move yet: the next resources are held by others. */
    WAITING,
    MOVING,
    ARRIVED,
    CANCELLED,
    /** The vehicle rejected the order or the fleet service could not deliver it. */
    FAILED;

    public boolean isActive() {
        return this == WAITING || this == MOVING;
    }
}
