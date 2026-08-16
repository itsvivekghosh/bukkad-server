package com.bhukkad.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SecurityEventLoggerTest {

    private final SecurityEventLogger logger = new SecurityEventLogger();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void events_withoutRequestContext_useUnknownIp() {
        assertDoesNotThrow(() -> {
            logger.logLoginSuccess(1L, "ab@example.com", "CUSTOMER");
            logger.logLoginFailure("a@example.com", "bad password");
            logger.logRegistration(2L, "not-an-email", "ADMIN");
            logger.logUnauthorizedAccess("user@example.com", "/api/admin");
            logger.logInvalidToken("expired");
            logger.logPasswordChange(3L, null);
        });
    }

    @Test
    void getClientIp_usesForwardedHeaderAndRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertDoesNotThrow(() -> logger.logLoginSuccess(1L, "user@example.com", "CUSTOMER"));

        when(request.getHeader("X-Forwarded-For")).thenReturn("");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        assertDoesNotThrow(() -> logger.logLoginFailure("user@example.com", "locked"));

        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        assertDoesNotThrow(() -> logger.logRegistration(1L, "user@example.com", "CUSTOMER"));
    }

    @Test
    void getClientIp_handlesBrokenRequestAttributes() {
        RequestContextHolder.setRequestAttributes(mock(org.springframework.web.context.request.RequestAttributes.class));
        assertDoesNotThrow(() -> logger.logInvalidToken("broken context"));
    }
}
