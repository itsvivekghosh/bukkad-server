package com.bhukkad.common.error;

import java.time.Instant;

/**
 * Structured API error body shared across services (plan §9 / error model).
 *
 * <p>Services map their domain exceptions onto this envelope so API clients
 * see a uniform shape regardless of which service handled the request.</p>
 *
 * @param status    HTTP status code
 * @param code      machine-readable error code (e.g. {@code DUPLICATE_REQUEST})
 * @param message   human-readable summary (never a stack trace)
 * @param traceId   correlation/trace id for log correlation
 * @param timestamp when the error occurred
 */
public record ApiError(int status, String code, String message, String traceId, Instant timestamp) {

    public static ApiError of(int status, String code, String message, String traceId) {
        return new ApiError(status, code, message, traceId, Instant.now());
    }
}
