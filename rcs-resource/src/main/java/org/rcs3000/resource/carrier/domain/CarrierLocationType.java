package org.rcs3000.resource.carrier.domain;

public enum CarrierLocationType {
    /** {@code locationRef} is a storage location code. */
    STORAGE,
    /** {@code locationRef} is a vehicle code. */
    VEHICLE,
    /** Not in the system's view, e.g. taken out by hand. */
    NONE
}
