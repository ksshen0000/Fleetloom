package org.fleetloom.common.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.error.CommonErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Converts exceptions into {@link ApiResponse} with the matching HTTP status.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException e) {
        return ResponseEntity.status(e.getErrorCode().httpStatus())
                .body(ApiResponse.error(e.getErrorCode().code(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage(),
                        (a, b) -> a));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), "request validation failed", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("unreadable request body", e);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), "malformed request body", null));
    }

    /** Invalid path variables or request parameters, e.g. a {@code @Pattern} on a path variable or an unknown enum. */
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Map<String, String>>> handleBadParameter(Exception e) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (e instanceof HandlerMethodValidationException v) {
            v.getParameterValidationResults().forEach(r -> errors.put(
                    r.getMethodParameter().getParameterName() == null ? "parameter" : r.getMethodParameter().getParameterName(),
                    r.getResolvableErrors().stream().map(MessageSourceResolvable::getDefaultMessage)
                            .filter(m -> m != null).findFirst().orElse("invalid")));
        } else if (e instanceof MethodArgumentTypeMismatchException m) {
            errors.put(m.getName(), "invalid value " + m.getValue());
        } else if (e instanceof MissingServletRequestParameterException m) {
            errors.put(m.getParameterName(), "required");
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), "invalid request parameter", errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        // Spring's own client errors (unknown path, wrong method, ...) keep their status
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            String code = response.getStatusCode().value() == 404 ? CommonErrorCode.NOT_FOUND.code()
                    : CommonErrorCode.BAD_REQUEST.code();
            return ResponseEntity.status(response.getStatusCode())
                    .body(ApiResponse.error(code, response.getBody().getDetail() == null ? e.getMessage()
                            : response.getBody().getDetail(), null));
        }
        log.error("unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(CommonErrorCode.INTERNAL_ERROR.code(), "internal error", null));
    }
}
