package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Lightweight production request logging filter. Logs only the HTTP summary
 * line (method, path, status, duration) and is subject to sampling via
 * {@link LoggingSampler}. Does NOT log request/response bodies in production.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ProductionRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProductionRequestLoggingFilter.class);
    private final LoggingSampler sampler;

    public ProductionRequestLoggingFilter(LoggingSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !sampler.shouldLogHttp();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdResolver.seedFrom(request);
        TraceContext.putSpanId(TraceContext.newSpanId());
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            log.info("HTTP | method={} | path={} | status={} | durationMs={} | traceId={}",
                request.getMethod(), request.getRequestURI(), response.getStatus(),
                duration, traceId);
            MDC.clear();
        }
    }
}
