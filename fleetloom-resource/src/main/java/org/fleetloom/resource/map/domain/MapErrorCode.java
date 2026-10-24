package org.fleetloom.resource.map.domain;

import org.fleetloom.common.error.ErrorCode;

public enum MapErrorCode implements ErrorCode {
    MAP_NOT_FOUND(404),
    MAP_CODE_DUPLICATE(409),
    MAP_IN_USE(409),
    MAP_VERSION_NOT_FOUND(404),
    MAP_VERSION_NOT_DRAFT(409),
    MAP_DRAFT_ALREADY_EXISTS(409),
    MAP_REVISION_CONFLICT(409),
    MAP_NO_ACTIVE_VERSION(404),
    MAP_VALIDATION_FAILED(422);

    private final int httpStatus;

    MapErrorCode(int httpStatus) {
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
