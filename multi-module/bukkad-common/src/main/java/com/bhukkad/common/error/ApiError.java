package com.bhukkad.common.error;

import java.time.Instant;
import java.util.Map;

/**
 * Uniform error envelope returned by every service (and aggregated by the
 * gateway). Never contains stack traces or internal details.
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Map<String, Object> details,
        Instant timestamp
) {
    public static ApiError of(ErrorCode code, String message, String traceId) {
        return new ApiError(code.name(), message, traceId, Map.of(), Instant.now());
    }

    public static ApiError of(ErrorCode code, String message, String traceId, Map<String, Object> details) {
        return new ApiError(code.name(), message, traceId, details, Instant.now());
    }
}
