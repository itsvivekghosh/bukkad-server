package com.bhukkad.security;

import com.bhukkad.config.MonitoringProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrometheusAuthFilterTest {

    private MonitoringProperties monitoringProperties;
    private PrometheusAuthFilter filter;

    @BeforeEach
    void setUp() {
        MonitoringProperties.Prometheus prometheus = new MonitoringProperties.Prometheus();
        monitoringProperties = new MonitoringProperties();
        monitoringProperties.setPrometheus(prometheus);
        filter = new PrometheusAuthFilter(monitoringProperties);
    }

    private MockHttpServletRequest req(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    @Test void shouldNotFilter_returnsTrueForNonPrometheus() {
        assertEquals(true, filter.shouldNotFilter(req("/api/v1/health/ping")));
    }

    @Test void shouldNotFilter_returnsFalseForPrometheus() {
        assertEquals(false, filter.shouldNotFilter(req("/actuator/prometheus")));
    }

    @Test void authNotRequired_passesThrough() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(false);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req("/actuator/prometheus"), new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test void authRequired_butNoTokenConfigured_returns403() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(true);
        monitoringProperties.getPrometheus().setBearerToken(null);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(req("/actuator/prometheus"), response, chain);

        assertEquals(403, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void authRequired_correctToken_passesThrough() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(true);
        monitoringProperties.getPrometheus().setBearerToken("secret-token");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = req("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer secret-token");

        filter.doFilterInternal(request, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
    }

    @Test void authRequired_wrongToken_returns401() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(true);
        monitoringProperties.getPrometheus().setBearerToken("secret-token");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = req("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void authRequired_missingHeader_returns401() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(true);
        monitoringProperties.getPrometheus().setBearerToken("secret-token");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(req("/actuator/prometheus"), response, chain);

        assertEquals(401, response.getStatus());
        verifyNoInteractions(chain);
    }

    @Test void authRequired_nonBearerHeader_returns401() throws Exception {
        monitoringProperties.getPrometheus().setRequireAuth(true);
        monitoringProperties.getPrometheus().setBearerToken("secret-token");
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = req("/actuator/prometheus");
        request.addHeader("Authorization", "Basic abc123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
    }
}
