package org.rcs3000.traffic.core;

import org.rcs3000.common.error.ErrorCode;

public enum TrafficErrorCode implements ErrorCode {
    VEHICLE_UNKNOWN(404),
    VEHICLE_NOT_ONLINE(409),
    VEHICLE_NOT_ON_NODE(409),
    ROUTE_ACTIVE(409),
    VEHICLE_BUSY(409),
    NO_ACTIVE_ROUTE(409),
    UNKNOWN_POINT(404),
    NO_ROUTE(422),
    MAP_UNAVAILABLE(503),
    FLEET_UNAVAILABLE(502);

    private final int httpStatus;

    TrafficErrorCode(int httpStatus) {
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
