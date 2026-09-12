package com.bhukkad.restaurant.api.dto.response;

import com.bhukkad.common.tracing.TraceContext;

import java.time.LocalDateTime;

/**
 * Standard API response envelope matching the monolith's
 * {@code com.bhukkad.dto.response.ApiResponse} so strangler route flips
 * never break the client contract. All fields serialize (including nulls),
 * exactly as the monolith's Jackson {@code ApiResponse} does.
 */
public record ApiResponse<T>(
        boolean success,
        String message,
        T data,
        LocalDateTime timestamp,
        String traceId,
        String spanId,
        String requestId
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                true, null, data, LocalDateTime.now(),
                TraceContext.currentTraceId(), TraceContext.currentSpanId(), TraceContext.currentRequestId());
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(
                true, message, data, LocalDateTime.now(),
                TraceContext.currentTraceId(), TraceContext.currentSpanId(), TraceContext.currentRequestId());
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(
                false, message, null, LocalDateTime.now(),
                TraceContext.currentTraceId(), TraceContext.currentSpanId(), TraceContext.currentRequestId());
    }
}