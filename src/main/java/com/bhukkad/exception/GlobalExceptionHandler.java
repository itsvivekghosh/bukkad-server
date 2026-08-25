package com.bhukkad.exception;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.logging.TraceContext;
import com.bhukkad.logging.alert.AlertService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AlertService alertService;

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimitExceeded(
            RateLimitExceededException ex) {
        log.warn("RateLimitExceeded | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "rate-limit", 429, 0);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(buildError(ex.getMessage()));
    }

    /**
     * Maps an abuse-detection block to {@code 429 Too Many Requests}.
     *
     * <p>Deliberately mirrors the rate-limit handler rather than returning {@code 403}:
     * the block is transient and drains as the detection window slides, and every HTTP
     * client already knows how to honour {@code Retry-After} on a 429. A 403 would
     * suggest a permanent authorization decision and push clients into a retry loop
     * with no backoff hint.
     *
     * <p>The alert channel is separate ({@code fraud-blocked} rather than
     * {@code rate-limit}) so that abuse spikes can be alerted on and tuned
     * independently of ordinary throttling noise.
     *
     * <p>The response body reuses the deliberately vague message carried on the
     * exception; it must never disclose which dimension (IP or device) tripped, nor
     * the configured threshold, or the limits become trivial to probe.
     */
    @ExceptionHandler(FraudBlockedException.class)
    public ResponseEntity<ApiResponse<Void>> handleFraudBlocked(FraudBlockedException ex) {
        log.warn("FraudBlocked | {} | eventType={} | traceId={} | requestId={}",
                ex.getMessage(), ex.getEventType(),
                TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "fraud-blocked", 429, 0);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(buildError(ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(
            ResourceNotFoundException ex, WebRequest request) {
        log.warn("ResourceNotFound | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError(ex.getMessage()));
    }

    /**
     * Spring MVC 6.1 throws NoResourceFoundException when a request path does
     * not match any controller mapping (it falls through to static-resource
     * resolution). Without this handler it surfaces as a 500; it must be a
     * 404 so unknown URLs and typos (e.g. a wrong toggle endpoint) fail
     * predictably instead of tripping the unexpected-error path.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            NoResourceFoundException ex, WebRequest request) {
        log.warn("NoResourceFound | {} | traceId={} | requestId={}",
                ex.getResourcePath(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("No such endpoint: " + ex.getResourcePath()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex, WebRequest request) {
        log.warn("BusinessException | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError(ex.getMessage()));
    }

    /**
     * Maps a duplicate idempotency-key request (already being processed) to
     * {@code 409 Conflict}, matching the documented API contract ("Already
     * processing"). More specific than the generic {@link BusinessException}
     * handler, so 400 stays reserved for genuine input/validation errors.
     */
    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateRequest(
            DuplicateRequestException ex, WebRequest request) {
        log.warn("DuplicateRequest | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError(ex.getMessage()));
    }

    /**
     * Maps an SSE stream that has reached its per-stream connection cap to
     * {@code 503 Service Unavailable}: the resource is healthy but temporarily
     * cannot accept more subscribers. Clients should retry with backoff (or
     * fall back to HTTP polling) rather than treating it as a permanent error.
     */
    @ExceptionHandler(SseCapacityExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleSseCapacityExceeded(
            SseCapacityExceededException ex, WebRequest request) {
        log.warn("SseCapacityExceeded | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError(ex.getMessage()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(
            UnauthorizedException ex, WebRequest request) {
        log.warn("Unauthorized | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "unauthorized", 401, 0);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(
            AccessDeniedException ex, WebRequest request) {
        log.warn("AccessDenied | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "access-denied", 403, 0);
        return new ResponseEntity<>(buildError("Access denied. You don't have permission."), HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex, WebRequest request) {
        log.warn("AuthenticationFailed | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "authentication-failed", 401, 0);
        return new ResponseEntity<>(buildError("Authentication failed"), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(
            BadCredentialsException ex, WebRequest request) {
        log.warn("BadCredentials | traceId={} | requestId={}",
                TraceContext.getTraceId(), TraceContext.getRequestId());
        alertService.alertHttpError("UNKNOWN", "bad-credentials", 401, 0);
        return new ResponseEntity<>(buildError("Invalid email or password"), HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = ((FieldError) error).getField();
            String message = error.getDefaultMessage();
            errors.put(field, message);
        });

        log.warn("ValidationFailed | {} | traceId={} | requestId={}",
                errors, TraceContext.getTraceId(), TraceContext.getRequestId());

        return new ResponseEntity<>(
                ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed")
                        .data(errors)
                        .timestamp(LocalDateTime.now())
                        .traceId(TraceContext.getTraceId())
                        .requestId(TraceContext.getRequestId())
                        .build(),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex, WebRequest request) {
        log.warn("MediaTypeNotAcceptable | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Media type not acceptable for this endpoint"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, WebRequest request) {
        log.warn("MediaTypeNotSupported | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Unsupported media type"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, WebRequest request) {
        log.warn("HttpMessageNotReadable | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Malformed request body"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParams(
            MissingServletRequestParameterException ex, WebRequest request) {
        log.warn("MissingParam | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Missing required parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(
            MissingRequestHeaderException ex, WebRequest request) {
        log.warn("MissingHeader | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Missing required header: " + ex.getHeaderName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        log.warn("TypeMismatch | {} | traceId={} | requestId={}",
                ex.getMessage(), TraceContext.getTraceId(), TraceContext.getRequestId());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(buildError("Invalid parameter value"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleRuntimeException(
            RuntimeException ex, WebRequest request) {
        log.error("RuntimeException | type={} | traceId={} | requestId={}",
                ex.getClass().getSimpleName(),
                TraceContext.getTraceId(), TraceContext.getRequestId());
        if (ex.getMessage() != null && ex.getMessage().length() > 500) {
            log.error("Exception message truncated for safety");
        } else {
            log.error("Exception message: {}", ex.getMessage());
        }
        log.error("RuntimeException stack trace", ex);
        alertService.alertException("GlobalExceptionHandler", ex.getMessage(), ex);
        return new ResponseEntity<>(buildError("An unexpected error occurred. Please try again later."),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex, WebRequest request) {
        log.error("UnexpectedException | type={} | traceId={} | requestId={}",
                ex.getClass().getSimpleName(),
                TraceContext.getTraceId(), TraceContext.getRequestId());
        log.error("UnexpectedException message={}", ex.getMessage(), ex);
        alertService.alertException("GlobalExceptionHandler", "Unexpected error", ex);
        return new ResponseEntity<>(
                buildError("An unexpected error occurred. Please try again later."),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ApiResponse<Void> buildError(String message) {
        return ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .traceId(TraceContext.getTraceId())
                .requestId(TraceContext.getRequestId())
                .build();
    }
}
