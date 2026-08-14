package com.bhukkad.security;

import com.bhukkad.config.MonitoringProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class PrometheusAuthFilter extends OncePerRequestFilter {

    private final MonitoringProperties monitoringProperties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        MonitoringProperties.Prometheus prometheus = monitoringProperties.getPrometheus();
        if (!prometheus.isRequireAuth()) {
            filterChain.doFilter(request, response);
            return;
        }

        String expectedToken = prometheus.getBearerToken();
        if (!StringUtils.hasText(expectedToken)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Prometheus endpoint is protected");
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")
                && expectedToken.equals(authHeader.substring(7))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing Prometheus bearer token");
    }
}
