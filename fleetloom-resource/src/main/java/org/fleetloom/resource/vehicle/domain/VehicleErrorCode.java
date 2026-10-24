package org.fleetloom.resource.vehicle.domain;

import org.fleetloom.common.error.ErrorCode;

public enum VehicleErrorCode implements ErrorCode {
    VEHICLE_TYPE_NOT_FOUND(404),
    VEHICLE_TYPE_CODE_DUPLICATE(409),
    VEHICLE_TYPE_IN_USE(409),
    VEHICLE_NOT_FOUND(404),
    VEHICLE_CODE_DUPLICATE(409),
    /** Another vehicle already has this manufacturer and serial number. */
    VEHICLE_IDENTITY_DUPLICATE(409),
    INVALID_CHARGE_THRESHOLDS(400);

    private final int httpStatus;

    VehicleErrorCode(int httpStatus) {
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
