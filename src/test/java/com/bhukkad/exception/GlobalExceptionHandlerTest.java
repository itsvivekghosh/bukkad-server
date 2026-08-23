package com.bhukkad.exception;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.logging.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private AlertService alertService;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(alertService);
    }

    @Test
    void handleRateLimitExceeded() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleRateLimitExceeded(
                new RateLimitExceededException("Too fast", 60));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }

    @Test
    void handleResourceNotFoundException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleResourceNotFoundException(
                new ResourceNotFoundException("Not found"), mock(WebRequest.class));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void handleBusinessException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBusinessException(
                new BusinessException("Invalid op"), mock(WebRequest.class));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleUnauthorizedException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleUnauthorizedException(
                new UnauthorizedException("Not authorized"), mock(WebRequest.class));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void handleValidationExceptions() {
        BindingResult bindingResult = mock(BindingResult.class);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleValidationExceptions(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleFraudBlocked() {
        FraudBlockedException ex = new FraudBlockedException("Fraud detected", "BRUTE_FORCE", 60);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleFraudBlocked(ex);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }

    @Test
    void handleAccessDeniedException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleAccessDeniedException(
                new org.springframework.security.access.AccessDeniedException("denied"), mock(WebRequest.class));
        assertEquals(HttpStatus.FORBIDDEN, resp.getStatusCode());
    }

    @Test
    void handleHttpMessageNotReadable() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleHttpMessageNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException("bad body"), mock(WebRequest.class));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleGenericException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleGlobalException(
                new RuntimeException("unexpected"), mock(WebRequest.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}