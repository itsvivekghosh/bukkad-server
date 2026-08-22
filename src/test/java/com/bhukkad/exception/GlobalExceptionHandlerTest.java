package com.bhukkad.exception;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.logging.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private AlertService alertService;

    private GlobalExceptionHandler handler;
    private final WebRequest request = mock(WebRequest.class);

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(alertService);
    }

    @Test
    void handleRateLimitExceeded() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException("slow down", 30));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("30", response.getHeaders().getFirst("Retry-After"));
        assertEquals("slow down", response.getBody().getMessage());
    }

    @Test
    void handleResourceNotFound() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleResourceNotFoundException(new ResourceNotFoundException("missing"), request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals("missing", response.getBody().getMessage());
        assertNotNull(response.getBody().getTimestamp());
    }

    @Test
    void handleBusinessException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException("invalid"), request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid", response.getBody().getMessage());
    }

    @Test
    void handleUnauthorizedException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnauthorizedException(new UnauthorizedException("nope"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("nope", response.getBody().getMessage());
    }

    @Test
    void handleAccessDenied() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"), request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Access denied. You don't have permission.", response.getBody().getMessage());
    }

    @Test
    void handleAuthenticationException() {
        AuthenticationException ex = new AuthenticationException("failed") {};
        ResponseEntity<ApiResponse<Void>> response = handler.handleAuthenticationException(ex, request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Authentication failed", response.getBody().getMessage());
    }

    @Test
    void handleBadCredentials() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBadCredentialsException(new BadCredentialsException("bad"), request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid email or password", response.getBody().getMessage());
    }

    @Test
    void handleValidationExceptions() throws Exception {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "email", "must not be blank"));
        MethodParameter parameter = new MethodParameter(String.class.getMethod("toString"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiResponse<Map<String, String>>> response = handler.handleValidationExceptions(ex);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation failed", response.getBody().getMessage());
        assertEquals("must not be blank", response.getBody().getData().get("email"));
    }

    @Test
    void handleRuntimeException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRuntimeException(new RuntimeException("boom"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        // Raw exception messages must never leak to clients.
        assertEquals("An unexpected error occurred. Please try again later.", response.getBody().getMessage());
    }

    @Test
    void handleGlobalException() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleGlobalException(new Exception("unexpected"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("An unexpected error occurred. Please try again later.", response.getBody().getMessage());
    }
}
