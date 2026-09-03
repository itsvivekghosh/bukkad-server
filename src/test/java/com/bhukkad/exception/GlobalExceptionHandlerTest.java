package com.bhukkad.exception;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.SseCapacityExceededException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.ratelimit.RateLimitExceededException;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void handleNoResourceFoundReturns404() {
        // A path that matches no controller mapping (e.g. a typo'd endpoint)
        // must yield 404, not fall through to the unexpected-error 500 path.
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.PUT, "/api/v1/restaurants/owner/365/toggle-open");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleNoResourceFound(ex, mock(WebRequest.class));
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("No such endpoint: /api/v1/restaurants/owner/365/toggle-open",
                resp.getBody().getMessage());
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
        FieldError fieldError = new FieldError("obj", "email", "Email is required");
        when(bindingResult.getAllErrors()).thenReturn(java.util.List.of(fieldError));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, bindingResult);
        ResponseEntity<ApiResponse<Map<String, String>>> resp = handler.handleValidationExceptions(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Email is required", resp.getBody().getData().get("email"));
    }

    @Test
    void handleFraudBlocked() {
        FraudBlockedException ex = new FraudBlockedException("Fraud detected", "BRUTE_FORCE", 60);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleFraudBlocked(ex);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
    }

    @Test
    void handleSseCapacityExceeded() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSseCapacityExceeded(
                new SseCapacityExceededException("Stream capacity reached for order 42"),
                mock(WebRequest.class));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Stream capacity reached for order 42", resp.getBody().getMessage());
    }

    @Test
    void handleDuplicateRequest_returns409() {
        DuplicateRequestException ex = new DuplicateRequestException("Duplicate order request is already being processed");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleDuplicateRequest(ex, mock(WebRequest.class));
        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Duplicate order request is already being processed", resp.getBody().getMessage());
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
                new org.springframework.http.converter.HttpMessageNotReadableException("bad body",
                        mock(org.springframework.http.HttpInputMessage.class)), mock(WebRequest.class));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleGenericException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleGlobalException(
                new RuntimeException("unexpected"), mock(WebRequest.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void handleAuthenticationException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleAuthenticationException(
                new AuthenticationException("bad auth") {}, mock(WebRequest.class));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void handleBadCredentialsException() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleBadCredentialsException(
                new BadCredentialsException("bad creds"), mock(WebRequest.class));
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void handleMediaTypeNotAcceptable() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMediaTypeNotAcceptable(
                new HttpMediaTypeNotAcceptableException("not acceptable"), mock(WebRequest.class));
        assertEquals(HttpStatus.NOT_ACCEPTABLE, resp.getStatusCode());
    }

    @Test
    void handleMediaTypeNotSupported() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMediaTypeNotSupported(
                new HttpMediaTypeNotSupportedException("unsupported"), mock(WebRequest.class));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
    }

    @Test
    void handleMissingParams() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("page", "int");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingParams(ex, mock(WebRequest.class));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleTypeMismatch() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", int.class, "page", null, null);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleTypeMismatch(ex, mock(WebRequest.class));
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void handleRuntimeException_shortMessage() {
        ResponseEntity<ApiResponse<Void>> resp = handler.handleRuntimeException(
                new RuntimeException("short error"), mock(WebRequest.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }

    @Test
    void handleRuntimeException_longMessage() {
        String longMsg = "x".repeat(501);
        ResponseEntity<ApiResponse<Void>> resp = handler.handleRuntimeException(
                new RuntimeException(longMsg), mock(WebRequest.class));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
    }
}