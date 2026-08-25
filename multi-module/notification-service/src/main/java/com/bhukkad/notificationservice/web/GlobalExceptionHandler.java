package com.bhukkad.notificationservice.web;

import com.bhukkad.common.error.ApiError;
import com.bhukkad.common.error.ErrorCode;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised exception handling: converts domain/validation failures into the
 * uniform {@link ApiError} envelope (same shape as every other service) so the
 * gateway can aggregate responses consistently. Never leaks stack traces.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "An unexpected error occurred");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, ErrorCode code, String message) {
        String traceId = MDC.get("traceId");
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, traceId != null ? traceId : ""));
    }
}
