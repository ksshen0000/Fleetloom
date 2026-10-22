package org.rcs3000.dispatch.core;

/**
 * Another service refused a call or could not be reached.
 *
 * @see #errorCode() the remote service's error code, e.g. {@code LOCATION_STATE_INVALID}, or
 *      {@code UNAVAILABLE} when it did not answer
 */
public class RemoteCallException extends RuntimeException {

    private final String errorCode;

    public RemoteCallException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
