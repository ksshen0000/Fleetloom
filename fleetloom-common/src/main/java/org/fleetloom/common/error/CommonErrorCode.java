package org.fleetloom.common.error;

public enum CommonErrorCode implements ErrorCode {
    BAD_REQUEST(400),
    NOT_FOUND(404),
    CONFLICT(409),
    INTERNAL_ERROR(500);

    private final int httpStatus;

    CommonErrorCode(int httpStatus) {
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
