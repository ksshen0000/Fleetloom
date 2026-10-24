package org.fleetloom.contracts.dispatch;

public enum OrderStatus {
    /** Accepted, waiting for a vehicle. */
    QUEUED,
    /** A vehicle is on it. */
    EXECUTING,
    /** Stopped by a problem (vehicle offline, route failed) or by hand; can be resumed or cancelled. */
    SUSPENDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    /** Not accepted, e.g. the carrier or target location was not available. */
    REJECTED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == REJECTED;
    }
}
