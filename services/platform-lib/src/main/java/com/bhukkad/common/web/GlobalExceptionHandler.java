package com.bhukkad.common.web;

import com.bhukkad.common.error.ApiError;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import com.bhukkad.common.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralised exception handling shared by every service (plan §9).
 *
 * <p>This is the microservices port of the monolith's
 * {@code com.bhukkad.exception.GlobalExceptionHandler}: it maps domain
 * exceptions onto the uniform {@link ApiError} envelope, always echoes the
 * traceId for log correlation, and never leaks stack traces or internals to
 * clients.</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimitExceeded(RateLimitExceededException ex) {
        log.warn("RateLimitExceeded | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(ApiError.of(429, "RATE_LIMIT_EXCEEDED", ex.getMessage(), TraceContext.currentTraceId()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        log.warn("ResourceNotFound | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.NOT_FOUND, 404, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(com.bhukkad.common.error.UpstreamUnavailableException.class)
    public ResponseEntity<ApiError> handleUpstreamUnavailable(
            com.bhukkad.common.error.UpstreamUnavailableException ex) {
        // Mesh outages are transient environment signals, not missing
        // resources: respond 503 so callers (and the API test suite) can
        // distinguish "does not exist" from "temporarily unreachable".
        log.warn("UpstreamUnavailable | upstream={} | traceId={}", ex.getUpstream(),
                TraceContext.currentTraceId());
        return json(HttpStatus.SERVICE_UNAVAILABLE, 503, "UPSTREAM_UNAVAILABLE",
                "The " + ex.getUpstream() + " service is temporarily unavailable. Please retry.");
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        log.warn("Unauthorized | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.UNAUTHORIZED, 401, "UNAUTHORIZED", ex.getMessage());
    }

    /**
     * Method-security / @PreAuthorize denials must be 403, not 500: without
     * this handler AccessDeniedException fell into the RuntimeException
     * catch-all and masked authorization failures as internal errors.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        log.warn("AccessDenied | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.FORBIDDEN, 403, "ACCESS_DENIED", "Insufficient permissions");
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateRequestException ex) {
        log.warn("DuplicateRequest | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.CONFLICT, 409, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        log.warn("BusinessException | code={} | {} | traceId={}",
                ex.getCode(), ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.BAD_REQUEST, 400, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
            } else {
                fieldErrors.put(error.getObjectName(), error.getDefaultMessage());
            }
        });
        log.warn("ValidationFailed | {} | traceId={}", fieldErrors, TraceContext.currentTraceId());
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "VALIDATION_FAILED", "Validation failed: " + fieldErrors,
                        TraceContext.currentTraceId(), java.time.Instant.now()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex) {
        return json(HttpStatus.BAD_REQUEST, 400, "MISSING_PARAM",
                "Missing required parameter: " + ex.getParameterName());
    }

    /**
     * Malformed/unreadable/JSON-type-mismatched request bodies (and missing
     * required bodies) are CLIENT errors: 400, never 500. Unhandled, this
     * class turned `{"qty":"{var}"}` or `[]` bodies into opaque INTERNAL_ERRORs.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        log.warn("HttpMessageNotReadable | {} | traceId={}", ex.getMessage(), TraceContext.currentTraceId());
        return json(HttpStatus.BAD_REQUEST, 400, "INVALID_BODY",
                "Malformed or unreadable request body");
    }

    /**
     * Persistence constraint violations surface as 409 (conflict) or 400-like
     * errors, never as an unhandled 500 with a leaked driver message.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            org.springframework.dao.DataIntegrityViolationException ex) {
        log.warn("DataIntegrityViolation | type={} | traceId={}",
                ex.getMostSpecificCause().getClass().getSimpleName(), TraceContext.currentTraceId());
        return json(HttpStatus.CONFLICT, 409, "DATA_CONFLICT",
                "Request conflicts with existing data");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return json(HttpStatus.BAD_REQUEST, 400, "MISSING_HEADER",
                "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return json(HttpStatus.BAD_REQUEST, 400, "INVALID_PARAM", "Invalid parameter value");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex) {
        log.error("RuntimeException | type={} | traceId={}", ex.getClass().getSimpleName(),
                TraceContext.currentTraceId(), ex);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, 500, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    private ResponseEntity<ApiError> json(HttpStatus status, int code, String errorCode, String message) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(ApiError.of(code, errorCode, message, TraceContext.currentTraceId()));
    }
}
