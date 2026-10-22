package org.rcs3000.common.web;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.error.CommonErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Parameter constraints checked by {@code @Validated} controllers. Separate from
 * {@link GlobalExceptionHandler} because Bean Validation is optional for services.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConstraintViolationHandler {

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handle(ConstraintViolationException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : e.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            errors.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), v.getMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(CommonErrorCode.BAD_REQUEST.code(), "invalid request parameter", errors));
    }
}
