package org.rcs3000.dispatch.core;

import org.rcs3000.common.error.ErrorCode;

public enum DispatchErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(404),
    INVALID_ORDER(400),
    /** The operation is not allowed in the order's current status. */
    ORDER_STATE_INVALID(409),
    /** The carrier is already on the vehicle; the order cannot simply be dropped. */
    CARRIER_ON_VEHICLE(409),
    VEHICLE_UNKNOWN(404),
    ORDER_CONCURRENT_MODIFICATION(409),
    TEMPLATE_NOT_FOUND(404),
    /** The standard template is what orders without a template use; it can be changed but not deleted. */
    TEMPLATE_PROTECTED(409);

    private final int httpStatus;

    DispatchErrorCode(int httpStatus) {
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
