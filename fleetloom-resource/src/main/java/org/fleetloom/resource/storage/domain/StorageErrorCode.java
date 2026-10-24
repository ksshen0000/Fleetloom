package org.fleetloom.resource.storage.domain;

import org.fleetloom.common.error.ErrorCode;

public enum StorageErrorCode implements ErrorCode {
    ZONE_NOT_FOUND(404),
    ZONE_CODE_DUPLICATE(409),
    ZONE_IN_USE(409),
    LOCATION_NOT_FOUND(404),
    LOCATION_CODE_DUPLICATE(409),
    /** The operation is not allowed in the location's current status. */
    LOCATION_STATE_INVALID(409),
    LOCK_OWNER_MISMATCH(409),
    CARRIER_TYPE_NOT_ALLOWED(409),
    ACCESS_POINT_UNKNOWN(422),
    NO_AVAILABLE_LOCATION(404),
    STORAGE_CONCURRENT_MODIFICATION(409);

    private final int httpStatus;

    StorageErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
