package com.bhukkad.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SecurityHeadersFilterTest {

    @Mock
    private FilterChain filterChain;

    private SecurityHeadersFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter(new SecurityHeadersProperties());
    }

    private MockHttpServletResponse doFilter(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    @Test
    void apiRequest_addsSecurityHeaders() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter(new MockHttpServletRequest("GET", "/api/v1/auth/login"));

        assertEquals("default-src 'self'; frame-ancestors 'none'", response.getHeader("Content-Security-Policy"));
        assertEquals("max-age=31536000; includeSubDomains; preload", response.getHeader("Strict-Transport-Security"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()", response.getHeader("Permissions-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Opener-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Resource-Policy"));
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void customHeaders_areAdded() throws ServletException, IOException {
        SecurityHeadersProperties props = new SecurityHeadersProperties();
        props.getCustomHeaders().put("X-Custom", "value");
        SecurityHeadersFilter customFilter = new SecurityHeadersFilter(props);

        MockHttpServletResponse response = new MockHttpServletResponse();
        customFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), response, filterChain);

        assertEquals("value", response.getHeader("X-Custom"));
    }

    @Test
    void disabledFilter_addsNoHeaders() throws ServletException, IOException {
        SecurityHeadersProperties props = new SecurityHeadersProperties();
        props.setEnabled(false);
        SecurityHeadersFilter disabled = new SecurityHeadersFilter(props);

        MockHttpServletResponse response = new MockHttpServletResponse();
        disabled.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), response, filterChain);

        assertNull(response.getHeader("Content-Security-Policy"));
        assertNull(response.getHeader("Strict-Transport-Security"));
        assertNull(response.getHeader("X-Frame-Options"));
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void swaggerUi_isSkipped() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html"));

        assertNull(response.getHeader("Content-Security-Policy"));
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void apiDocs_isSkipped() throws ServletException, IOException {
        MockHttpServletResponse response = doFilter(new MockHttpServletRequest("GET", "/v3/api-docs"));

        assertNull(response.getHeader("Content-Security-Policy"));
        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}