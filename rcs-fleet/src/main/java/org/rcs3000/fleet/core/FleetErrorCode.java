package org.rcs3000.fleet.core;

import org.rcs3000.common.error.ErrorCode;

public enum FleetErrorCode implements ErrorCode {
    VEHICLE_NOT_FOUND(404),
    VEHICLE_NOT_ONLINE(409),
    NO_ACTIVE_ORDER(409),
    ROUTE_NOT_CONTINUOUS(409),
    INVALID_ROUTE(400);

    private final int httpStatus;

    FleetErrorCode(int httpStatus) {
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
