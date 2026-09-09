package com.bhukkad.common.web;

import com.bhukkad.common.web.VersionProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VersionHeaderFilterTest {

    private VersionProperties versionProperties;
    private VersionHeaderFilter filter;

    @BeforeEach
    void setUp() {
        versionProperties = new VersionProperties();
        versionProperties.setCurrentVersion("2");
        versionProperties.setDeprecatedVersions(List.of("1"));
        versionProperties.setUnsupportedVersions(List.of("0"));
        filter = new VersionHeaderFilter(versionProperties);
    }

    @Test
    void noVersionHeader_usesCurrentVersion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals("2", response.getHeader("X-API-Version"));
        assertNull(response.getHeader("Warning"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void deprecatedVersion_addsWarningHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Version", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals("2", response.getHeader("X-API-Version"));
        assertNotNull(response.getHeader("Warning"));
        assertTrue(response.getHeader("Warning").contains("Deprecated"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void unsupportedVersion_returns400() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Version", "0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void altHeaderXApiVersion_isHonored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Version", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertNotNull(response.getHeader("Warning"));
        verify(chain).doFilter(request, response);
    }

    @Test
    void currentVersion_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Version", "2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertNull(response.getHeader("Warning"));
        verify(chain).doFilter(request, response);
    }
}