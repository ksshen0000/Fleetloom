package org.fleetloom.common.api;

import org.slf4j.MDC;

/**
 * Uniform REST response envelope: {@code { code, message, data, traceId }}.
 *
 * <p>{@code code} is {@link #SUCCESS_CODE} on success, otherwise an {@code ErrorCode#code()}.
 */
public record ApiResponse<T>(String code, String message, T data, String traceId) {

    public static final String SUCCESS_CODE = "OK";

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "success", data, currentTraceId());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(String code, String message, T data) {
        return new ApiResponse<>(code, message, data, currentTraceId());
    }

    private static String currentTraceId() {
        return MDC.get("traceId");
    }
}
