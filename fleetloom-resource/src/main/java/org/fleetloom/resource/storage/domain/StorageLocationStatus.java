package org.fleetloom.resource.storage.domain;

public enum StorageLocationStatus {
    EMPTY,
    OCCUPIED,
    /** Reserved for a carrier being brought in. */
    LOCKED_IN,
    /** Its carrier is about to be taken away. */
    LOCKED_OUT,
    DISABLED;

    public boolean isLocked() {
        return this == LOCKED_IN || this == LOCKED_OUT;
    }
}
