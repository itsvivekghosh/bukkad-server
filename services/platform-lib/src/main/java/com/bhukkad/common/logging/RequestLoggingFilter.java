package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Seeds the MDC with trace/request ids for every HTTP request and logs the
 * request summary (port of {@code com.bhukkad.logging.RequestLoggingFilter}).
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = TraceIdResolver.seedFrom(request);
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
        }
    }
}
