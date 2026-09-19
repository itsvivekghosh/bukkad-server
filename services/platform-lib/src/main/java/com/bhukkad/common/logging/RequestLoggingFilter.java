package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Seeds the MDC with trace/request ids for every HTTP request and logs the
 * request summary (port of {@code com.bhukkad.logging.RequestLoggingFilter}).
 * Auto-registered via {@link Component} for all Servlet-based services that
 * scan package {@code com.bhukkad.common}.
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final Environment environment;
    private final LoggingSampler sampler;

    @Value("${spring.application.name:unknown}")
    private String serviceName;

    public RequestLoggingFilter(Environment environment, LoggingSampler sampler) {
        this.environment = environment;
        this.sampler = sampler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // In non-dev environments, sample HTTP request logging to avoid log flood
        if (isDevProfile()) {
            return false;
        }
        return !sampler.shouldLogHttp();
    }

    private boolean isDevProfile() {
        String[] activeProfiles = environment.getActiveProfiles();
        for (String profile : activeProfiles) {
            if ("dev".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdResolver.seedFrom(request);
        TraceContext.putSpanId(TraceContext.newSpanId());
        MDC.put("service", serviceName);
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("HTTP | method={} | path={} | status={} | durationMs={} | traceId={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    duration, traceId);
            MDC.remove(TraceContext.TRACE_ID);
            MDC.remove(TraceContext.REQUEST_ID);
            MDC.remove(TraceContext.SPAN_ID);
            MDC.remove("service");
        }
    }
}
