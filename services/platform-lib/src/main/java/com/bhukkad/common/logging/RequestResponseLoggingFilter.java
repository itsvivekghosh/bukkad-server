package com.bhukkad.common.logging;

import com.bhukkad.common.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dev-only filter that logs HTTP request/response headers and bodies for
 * deep debugging. Runs before controller code and restores the cached body
 * so downstream filters/controllers can read it normally.
 *
 * <p>Active only when {@code app.debug=true} or the {@code dev} profile is
 * active. Never enabled in production.</p>
 */
@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("HTTP_BODY");

    @Value("${app.debug:false}")
    private boolean debugMode;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only run when debug mode is on
        if (!debugMode) {
            return true;
        }
        // Skip multipart/form-data to avoid huge base64 bodies
        String contentType = request.getContentType();
        return contentType != null && contentType.contains("multipart/form-data");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, 1024 * 1024);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long duration = System.currentTimeMillis() - start;

            if (log.isDebugEnabled()) {
                log.debug("HTTP_REQUEST | method={} | path={} | query={} | headers={} | body={}",
                    wrappedRequest.getMethod(),
                    wrappedRequest.getRequestURI(),
                    wrappedRequest.getQueryString(),
                    headersToString(wrappedRequest),
                    truncate(getRequestBody(wrappedRequest), 4096),
                    TraceContext.currentTraceId()
                );

                log.debug("HTTP_RESPONSE | status={} | headers={} | body={} | durationMs={} | traceId={}",
                    wrappedResponse.getStatus(),
                    headersToString(wrappedResponse),
                    truncate(getResponseBody(wrappedResponse), 4096),
                    duration,
                    TraceContext.currentTraceId()
                );
            }

            // Must copy cached response body back to the actual response
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        byte[] content = request.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String getResponseBody(ContentCachingResponseWrapper response) {
        byte[] content = response.getContentAsByteArray();
        if (content.length == 0) {
            return "";
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private String headersToString(HttpServletRequest request) {
        List<String> headers = new ArrayList<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = request.getHeader(name);
            // Redact auth headers even in debug mode
            if (name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie")) {
                value = "***REDACTED***";
            }
            headers.add(name + "=" + value);
        }
        return headers.stream().collect(Collectors.joining(", ", "{", "}"));
    }

    private String headersToString(HttpServletResponse response) {
        List<String> headers = new ArrayList<>();
        response.getHeaderNames().forEach(name -> {
            String value = response.getHeader(name);
            headers.add(name + "=" + value);
        });
        return headers.stream().collect(Collectors.joining(", ", "{", "}"));
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            return value.substring(0, maxLength) + "...[truncated " + (value.length() - maxLength) + " chars]";
        }
        return value;
    }
}
