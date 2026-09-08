package com.bhukkad.common.web;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.ratelimit.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void resourceNotFound_mapsTo404() {
        ResponseEntity<?> response = handler.handleNotFound(new ResourceNotFoundException("Order not found: 5"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().toString()).contains("Order not found: 5");
    }

    @Test
    void duplicateRequest_mapsTo409() {
        ResponseEntity<?> response = handler.handleDuplicate(new DuplicateRequestException("Already processing"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void businessException_mapsTo400() {
        ResponseEntity<?> response = handler.handleBusiness(new BusinessException("INVALID", "bad input"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rateLimitExceeded_mapsTo429WithRetryAfter() {
        ResponseEntity<?> response = handler.handleRateLimitExceeded(new RateLimitExceededException("slow down", 12));
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("12");
    }

    @Test
    void validation_mapsTo400WithFieldErrors() throws Exception {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        BindingResult bindingResult = mock(BindingResult.class);
        when(ex.getBindingResult()).thenReturn(bindingResult);
        when(bindingResult.getAllErrors()).thenReturn(java.util.List.of(
                new org.springframework.validation.FieldError("order", "quantity", "must be positive")));

        ResponseEntity<?> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().toString()).contains("quantity");
    }

    @Test
    void runtimeException_mapsTo500Generic() {
        ResponseEntity<?> response = handler.handleRuntime(new IllegalStateException("db down"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().toString()).contains("unexpected error");
        assertThat(response.getBody().toString()).doesNotContain("db down");
    }

    @Test
    void typeMismatch_mapsTo400() {
        ResponseEntity<?> response = handler.handleTypeMismatch(
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", Long.class, "orderId", null, null));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void upstreamUnavailable_mapsTo503WithRetryableBody() {
        com.bhukkad.common.error.UpstreamUnavailableException ex =
                new com.bhukkad.common.error.UpstreamUnavailableException(
                        "restaurant", new IllegalStateException("connection refused"));

        ResponseEntity<?> response = handler.handleUpstreamUnavailable(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().toString()).contains("restaurant");
        assertThat(response.getBody().toString()).contains("retry");
        // Must NOT read as "not found": the resource may well exist.
        assertThat(response.getBody().toString()).doesNotContain("not found");
    }
}
