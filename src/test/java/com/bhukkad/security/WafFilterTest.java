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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class WafFilterTest {

    @Mock
    private FilterChain filterChain;

    private WafFilter wafFilter;

    @BeforeEach
    void setUp() {
        wafFilter = new WafFilter();
    }

    private void doFilter(MockHttpServletRequest request) throws ServletException, IOException {
        wafFilter.doFilter(request, new MockHttpServletResponse(), filterChain);
    }

    @Test
    void cleanRequest_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("keyword", "pizza");

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sqliTautology_isBlocked() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("name", "foo' OR '1'='1");

        assertThrows(com.bhukkad.exception.BusinessException.class, () -> doFilter(request));
        verifyNoInteractions(filterChain);
    }

    @Test
    void sqliUnionSelect_isBlocked() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("q", "x UNION SELECT password FROM users");

        assertThrows(com.bhukkad.exception.BusinessException.class, () -> doFilter(request));
    }

    @Test
    void sqliComment_isBlocked() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("id", "1--");

        assertThrows(com.bhukkad.exception.BusinessException.class, () -> doFilter(request));
    }

    @Test
    void xssScriptTag_isBlocked() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addParameter("comment", "<script>alert(1)</script>");

        assertThrows(com.bhukkad.exception.BusinessException.class, () -> doFilter(request));
    }

    @Test
    void xssJavascriptUri_isBlocked() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("url", "javascript:alert(1)");

        assertThrows(com.bhukkad.exception.BusinessException.class, () -> doFilter(request));
    }

    @Test
    void longValue_ignored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("q", "a".repeat(5000));

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
