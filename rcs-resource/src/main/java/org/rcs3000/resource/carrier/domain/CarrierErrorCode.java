package org.rcs3000.resource.carrier.domain;

import org.rcs3000.common.error.ErrorCode;

public enum CarrierErrorCode implements ErrorCode {
    CARRIER_TYPE_NOT_FOUND(404),
    CARRIER_TYPE_CODE_DUPLICATE(409),
    CARRIER_TYPE_IN_USE(409),
    CARRIER_NOT_FOUND(404),
    CARRIER_CODE_DUPLICATE(409),
    /** The operation is not allowed where the carrier currently is. */
    CARRIER_STATE_INVALID(409),
    CARRIER_CONCURRENT_MODIFICATION(409);

    private final int httpStatus;

    CarrierErrorCode(int httpStatus) {
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
