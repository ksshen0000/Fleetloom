package org.rcs3000.common.error;

/**
 * Expected business failure. Carries an {@link ErrorCode} and optional structured details
 * that are returned to the client in {@code ApiResponse.data}.
 */
public class BizException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final transient Object details;

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
