package org.fleetloom.common.error;

/**
 * A business error code. Each service defines its own enum implementing this interface.
 */
public interface ErrorCode {

    /** Stable, machine-readable code returned to clients, e.g. {@code MAP_NOT_FOUND}. */
    String code();

    /** HTTP status the error maps to. */
    int httpStatus();
}
