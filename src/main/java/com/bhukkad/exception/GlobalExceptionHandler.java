package com.bhukkad.exception;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.logging.LoggingConstants;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(
            ResourceNotFoundException ex, WebRequest request) {
        log.warn("Resource not found | TraceId: {} | Path: {} | Message: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                ex.getMessage());

        return new ResponseEntity<>(
                buildErrorResponse(ex.getMessage()),
                HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex, WebRequest request) {
        log.warn("Business rule violation | TraceId: {} | Path: {} | Message: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                ex.getMessage());

        return new ResponseEntity<>(
                buildErrorResponse(ex.getMessage()),
                HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorizedException(
            UnauthorizedException ex, WebRequest request) {
        log.warn("Unauthorized access | TraceId: {} | Path: {} | Message: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                ex.getMessage());

        return new ResponseEntity<>(
                buildErrorResponse(ex.getMessage()),
                HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(
            AuthenticationException ex, WebRequest request) {
        log.warn("Authentication failed | TraceId: {} | Path: {} | Message: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                ex.getMessage());

        return new ResponseEntity<>(
                buildErrorResponse("Authentication failed: " + ex.getMessage()),
                HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(
            BadCredentialsException ex, WebRequest request) {
        log.warn("Bad credentials | TraceId: {} | Path: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false));

        return new ResponseEntity<>(
                buildErrorResponse("Invalid email or password"),
                HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationExceptions(
            MethodArgumentNotValidException ex, WebRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Validation failed | TraceId: {} | Path: {} | Errors: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                errors);

        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                .success(false)
                .message("Validation failed")
                .data(errors)
                .timestamp(LocalDateTime.now())
                .build();

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGlobalException(
            Exception ex, WebRequest request) {
        log.error("Unexpected error | TraceId: {} | Path: {} | Error: {} | Message: {}",
                MDC.get(LoggingConstants.TRACE_ID),
                request.getDescription(false),
                ex.getClass().getSimpleName(),
                ex.getMessage(),
                ex);

        return new ResponseEntity<>(
                buildErrorResponse("An unexpected error occurred. Please try again later."),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ApiResponse<Void> buildErrorResponse(String message) {
        return ApiResponse.<Void>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}