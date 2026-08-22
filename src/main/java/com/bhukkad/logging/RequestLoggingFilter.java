package com.bhukkad.logging;

import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.metrics.EndpointSloMetrics;
import com.bhukkad.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Logger perfLogger = LoggerFactory.getLogger(LoggingConstants.PERFORMANCE_LOGGER);

    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final AlertService alertService;
    private final EndpointSloMetrics endpointSloMetrics;

    @Value("${app.debug:false}")
    private boolean debugMode;

    private static final String[] SKIP_PATTERNS = {
            "/swagger-ui", "/v3/api-docs", "/api-docs",
            "/actuator", "/favicon.ico", "/webjars",
            "/api/v1/health", "/health"
    };

    public RequestLoggingFilter(UserRepository userRepository, AlertService alertService, EndpointSloMetrics endpointSloMetrics) {
        this.userRepository = userRepository;
        this.alertService = alertService;
        this.endpointSloMetrics = endpointSloMetrics;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String traceId = TraceIdResolver.resolveTraceId(request);
        String requestId = TraceIdResolver.resolveRequestId(request);
        String spanId = TraceContext.newSpanId();

        if (shouldSkip(request)) {
            attachTraceHeaders(response, traceId, requestId);
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        CachedBodyHttpServletResponse wrappedResponse = new CachedBodyHttpServletResponse(response);

        long startTime = System.currentTimeMillis();

        try {
            setMDCContext(wrappedRequest, traceId, requestId, spanId);
            attachTraceHeaders(wrappedResponse, traceId, requestId);

            logIncomingRequest(wrappedRequest, traceId, requestId);

            filterChain.doFilter(wrappedRequest, wrappedResponse);

        } finally {
            long duration = System.currentTimeMillis() - startTime;
            endpointSloMetrics.record(wrappedRequest.getMethod(), wrappedRequest.getRequestURI(),
                    duration, wrappedResponse.getStatus());
            populateUserContext();
            logCompletedRequest(wrappedRequest, wrappedResponse, duration, traceId, requestId);
            logPerformance(wrappedRequest, duration);
            evaluateAlerts(wrappedRequest, wrappedResponse, duration);
            MDC.clear();
        }
    }

    private void attachTraceHeaders(HttpServletResponse response, String traceId, String requestId) {
        response.setHeader(LoggingConstants.HEADER_TRACE_ID, traceId);
        response.setHeader(LoggingConstants.HEADER_REQUEST_ID, requestId);
        response.setHeader("X-Timestamp", Instant.now().toString());
        response.setHeader(LoggingConstants.HEADER_EXPOSE,
                LoggingConstants.HEADER_TRACE_ID + ", " + LoggingConstants.HEADER_REQUEST_ID + ", X-Timestamp");
    }

    private void evaluateAlerts(CachedBodyHttpServletRequest request,
                                CachedBodyHttpServletResponse response,
                                long duration) {
        int status = response.getStatus();
        alertService.alertSlowRequest(request.getMethod(), request.getRequestURI(), duration, status);
        alertService.alertHttpError(request.getMethod(), request.getRequestURI(), status, duration);
    }

    // ==================== INCOMING REQUEST LOG ====================

    private void logIncomingRequest(CachedBodyHttpServletRequest request, String traceId, String requestId) {
        try {
            Map<String, Object> logMap = new LinkedHashMap<>();
            logMap.put("event", "HTTP_REQUEST_RECEIVED");
            logMap.put("traceId", traceId);
            logMap.put("requestId", requestId);
            logMap.put("timestamp", Instant.now().toString());
            logMap.put("method", request.getMethod());
            logMap.put("uri", request.getRequestURI());
            logMap.put("protocol", request.getProtocol());
            logMap.put("ip", getClientIpAddress(request));

            Map<String, String[]> paramMap = request.getParameterMap();
            if (!paramMap.isEmpty()) {
                Map<String, Object> params = new LinkedHashMap<>();
                paramMap.forEach((key, values) -> params.put(key, values.length == 1 ? values[0] : Arrays.asList(values)));
                logMap.put("queryParams", params);
            }

            if (debugMode) {
                logMap.put("url", request.getRequestURL().toString());
                Map<String, String> headers = new LinkedHashMap<>();
                Enumeration<String> headerNames = request.getHeaderNames();
                if (headerNames != null) {
                    while (headerNames.hasMoreElements()) {
                        String name = headerNames.nextElement();
                        headers.put(name, LogSanitizer.sanitizeHeaderValue(name, request.getHeader(name)));
                    }
                }
                logMap.put("headers", headers);
            }

            String body = request.getBody();
            String contentType = request.getContentType();
            if (body != null && !body.isEmpty()) {
                if (LogSanitizer.isBinaryContent(contentType)) {
                    logMap.put("body", "[BINARY:" + contentType + "]");
                } else {
                    String sanitized = LogSanitizer.sanitizeBody(body);
                    try {
                        logMap.put("body", objectMapper.readValue(sanitized, Object.class));
                    } catch (Exception e) {
                        logMap.put("body", sanitized);
                    }
                }
            }

            log.info(objectMapper.writeValueAsString(logMap));

        } catch (Exception e) {
            log.info("HTTP_REQUEST_RECEIVED traceId={} requestId={} method={} uri={} ip={}",
                    traceId, requestId, request.getMethod(), request.getRequestURI(), getClientIpAddress(request));
        }
    }

    // ==================== COMPLETED REQUEST LOG ====================

    private void logCompletedRequest(CachedBodyHttpServletRequest request,
                                     CachedBodyHttpServletResponse response,
                                     long duration, String traceId, String requestId) {
        try {
            Map<String, Object> logMap = new LinkedHashMap<>();
            logMap.put("event", "HTTP_REQUEST_COMPLETED");
            logMap.put("traceId", traceId);
            logMap.put("spanId", MDC.get(LoggingConstants.SPAN_ID));
            logMap.put("requestId", requestId);
            logMap.put("timestamp", Instant.now().toString());
            logMap.put("method", request.getMethod());
            logMap.put("uri", request.getRequestURI());
            logMap.put("status", response.getStatus());
            logMap.put("statusText", getStatusText(response.getStatus()));
            logMap.put("durationMs", duration);

            String userId = MDC.get(LoggingConstants.USER_ID);
            String email = MDC.get(LoggingConstants.USER_EMAIL);
            String role = MDC.get(LoggingConstants.USER_ROLE);
            if (userId != null) logMap.put("userId", userId);
            if (email != null) logMap.put("email", email);
            if (role != null) logMap.put("role", role);

            if (debugMode) {
                Map<String, String> headers = new LinkedHashMap<>();
                for (String name : response.getHeaderNames()) {
                    headers.put(name, LogSanitizer.sanitizeHeaderValue(name, response.getHeader(name)));
                }
                if (!headers.isEmpty()) logMap.put("headers", headers);
            }

            String body = response.getBody();
            String contentType = response.getContentType();
            if (body != null && !body.isEmpty()) {
                if (LogSanitizer.isBinaryContent(contentType)) {
                    logMap.put("body", "[BINARY:" + contentType + "]");
                } else {
                    String sanitized = LogSanitizer.sanitizeBody(body);
                    try {
                        logMap.put("body", objectMapper.readValue(sanitized, Object.class));
                    } catch (Exception e) {
                        logMap.put("body", sanitized);
                    }
                }
            }

            if (duration > 3000) logMap.put("perfLevel", "CRITICAL");
            else if (duration > 1000) logMap.put("perfLevel", "SLOW");
            else if (duration > 500) logMap.put("perfLevel", "MODERATE");
            else logMap.put("perfLevel", "FAST");

            String json = objectMapper.writeValueAsString(logMap);

            if (response.getStatus() >= 500) log.error(json);
            else if (response.getStatus() >= 400) log.warn(json);
            else log.info(json);

        } catch (Exception e) {
            log.info("HTTP_REQUEST_COMPLETED traceId={} requestId={} method={} uri={} status={} duration={}ms",
                    traceId, requestId, request.getMethod(), request.getRequestURI(), response.getStatus(), duration);
        }
    }

    // ==================== MDC ====================

    private void setMDCContext(HttpServletRequest request, String traceId, String requestId, String spanId) {
        MDC.put(LoggingConstants.TRACE_ID, traceId);
        MDC.put(LoggingConstants.SPAN_ID, spanId);
        MDC.put(LoggingConstants.REQUEST_ID, requestId);
        MDC.put(LoggingConstants.REQUEST_PATH, request.getRequestURI());
        MDC.put(LoggingConstants.REQUEST_METHOD, request.getMethod());
        MDC.put(LoggingConstants.IP_ADDRESS, getClientIpAddress(request));
        MDC.put(LoggingConstants.TIMESTAMP, Instant.now().toString());
        populateUserContext();
    }

    private void populateUserContext() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
                String email = auth.getName();
                MDC.put(LoggingConstants.USER_EMAIL, email);
                if (!auth.getAuthorities().isEmpty()) {
                    MDC.put(LoggingConstants.USER_ROLE, auth.getAuthorities().iterator().next().getAuthority());
                }
                userRepository.findByEmail(email).ifPresent(user ->
                        MDC.put(LoggingConstants.USER_ID, String.valueOf(user.getId())));
            }
        } catch (Exception e) {
            log.debug("Could not populate user context: {}", e.getMessage());
        }
    }

    // ==================== PERFORMANCE ====================

    private void logPerformance(HttpServletRequest request, long duration) {
        try {
            Map<String, Object> perf = new LinkedHashMap<>();
            perf.put("event", "PERFORMANCE_METRIC");
            perf.put("method", request.getMethod());
            perf.put("uri", request.getRequestURI());
            perf.put("durationMs", duration);
            perf.put("traceId", MDC.get(LoggingConstants.TRACE_ID));
            perf.put("spanId", MDC.get(LoggingConstants.SPAN_ID));
            perf.put("requestId", MDC.get(LoggingConstants.REQUEST_ID));
            String userId = MDC.get(LoggingConstants.USER_ID);
            if (userId != null) perf.put("userId", userId);

            if (duration > 3000) { perf.put("level", "CRITICAL"); perfLogger.error(objectMapper.writeValueAsString(perf)); }
            else if (duration > 1000) { perf.put("level", "SLOW"); perfLogger.warn(objectMapper.writeValueAsString(perf)); }
            else if (duration > 500) { perf.put("level", "MODERATE"); perfLogger.info(objectMapper.writeValueAsString(perf)); }
            else { perf.put("level", "FAST"); perfLogger.debug(objectMapper.writeValueAsString(perf)); }
        } catch (Exception e) {
            perfLogger.debug("PERF traceId={} requestId={} {} {} {}ms",
                    MDC.get(LoggingConstants.TRACE_ID),
                    MDC.get(LoggingConstants.REQUEST_ID),
                    request.getMethod(), request.getRequestURI(), duration);
        }
    }

    // ==================== HELPERS ====================

    private String getClientIpAddress(HttpServletRequest request) {
        String[] headers = {"X-Forwarded-For", "Proxy-Client-IP", "WL-Proxy-Client-IP", "HTTP_X_FORWARDED_FOR", "HTTP_CLIENT_IP"};
        for (String header : headers) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) return ip.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean shouldSkip(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String p : SKIP_PATTERNS) { if (uri.contains(p)) return true; }
        return false;
    }

    private String getStatusText(int code) {
        return switch (code) {
            case 200 -> "OK"; case 201 -> "Created"; case 204 -> "No Content";
            case 400 -> "Bad Request"; case 401 -> "Unauthorized"; case 403 -> "Forbidden";
            case 404 -> "Not Found"; case 405 -> "Method Not Allowed"; case 409 -> "Conflict";
            case 500 -> "Internal Server Error"; case 502 -> "Bad Gateway"; case 503 -> "Service Unavailable";
            default -> "Unknown";
        };
    }
}
