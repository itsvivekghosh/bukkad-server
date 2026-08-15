package com.bhukkad.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Resolves or generates distributed trace and request identifiers.
 * Supports inbound propagation via common headers and W3C traceparent.
 */
public final class TraceIdResolver {

    private TraceIdResolver() {
    }

    public static String resolveTraceId(HttpServletRequest request) {
        String inbound = firstNonBlank(
                request.getHeader(LoggingConstants.HEADER_TRACE_ID),
                request.getHeader(LoggingConstants.HEADER_CORRELATION_ID),
                parseTraceParent(request.getHeader(LoggingConstants.HEADER_TRACEPARENT)));
        return normalize(inbound, 16);
    }

    public static String resolveRequestId(HttpServletRequest request) {
        String inbound = firstNonBlank(
                request.getHeader(LoggingConstants.HEADER_REQUEST_ID),
                request.getHeader(LoggingConstants.HEADER_CORRELATION_ID));
        return normalize(inbound, 8);
    }

    static String parseTraceParent(String traceParent) {
        if (!StringUtils.hasText(traceParent)) {
            return null;
        }
        String[] parts = traceParent.trim().split("-");
        if (parts.length >= 2 && parts[1].length() >= 16) {
            return parts[1].substring(0, 16);
        }
        return null;
    }

    private static String normalize(String value, int length) {
        if (!StringUtils.hasText(value)) {
            return generateId(length);
        }
        String cleaned = value.replace("-", "").trim();
        if (cleaned.length() > length) {
            return cleaned.substring(0, length);
        }
        return cleaned;
    }

    private static String generateId(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
