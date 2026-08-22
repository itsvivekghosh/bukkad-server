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

    private MockHttpServletResponse doFilter(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        wafFilter.doFilter(request, response, filterChain);
        return response;
    }

    @Test
    void cleanRequest_passesThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("keyword", "pizza");

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sqliTautology_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("name", "foo' OR '1'='1");

        MockHttpServletResponse response = doFilter(request);
        assertEquals(400, response.getStatus());
        verifyNoInteractions(filterChain);
    }

    @Test
    void sqliUnionSelect_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("q", "x UNION SELECT password FROM users");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void sqliComment_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("id", "1--");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void xssScriptTag_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addParameter("comment", "<script>alert(1)</script>");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void xssJavascriptUri_isBlocked() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/restaurants");
        request.addParameter("url", "javascript:alert(1)");

        assertEquals(400, doFilter(request).getStatus());
    }

    @Test
    void longValue_ignored() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/search");
        request.addParameter("q", "a".repeat(5000));

        doFilter(request);

        verify(filterChain).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
