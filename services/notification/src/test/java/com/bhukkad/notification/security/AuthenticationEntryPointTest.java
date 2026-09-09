package com.bhukkad.notification.security;

import com.bhukkad.notification.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationEntryPointTest {

    @Test
    void commence_writesUnauthorizedJson() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(mock(java.io.PrintWriter.class));
        AuthenticationException exception = mock(AuthenticationException.class);

        SecurityConfig config = new SecurityConfig();
        AuthenticationEntryPoint entryPoint = config.restAuthenticationEntryPoint();

        entryPoint.commence(request, response, exception);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setContentType("application/json");
        verify(response.getWriter()).write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
    }
}
