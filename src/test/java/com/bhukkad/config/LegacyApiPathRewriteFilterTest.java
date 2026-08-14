package com.bhukkad.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyApiPathRewriteFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    private final LegacyApiPathRewriteFilter filter = new LegacyApiPathRewriteFilter();

    @Test
    void rewritesLegacyApiPathToV1() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/orders/customer/1");

        filter.doFilterInternal(request, response, filterChain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(filterChain).doFilter(captor.capture(), any());
        assertEquals("/api/v1/orders/customer/1", captor.getValue().getRequestURI());
    }

    @Test
    void leavesVersionedPathsUnchanged() throws ServletException, IOException {
        when(request.getRequestURI()).thenReturn("/api/v1/orders/customer/1");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
