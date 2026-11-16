package com.bhukkad.engagement.chat.api.dto.response;

/**
 * Standard API response wrapper for chat operations.
 */
public record ChatResponse<T>(
        boolean success,
        String message,
        T data,
        String errorCode
) {
    public static <T> ChatResponse<T> success(T data) {
        return new ChatResponse<>(true, "Operation successful", data, null);
    }

    public static <T> ChatResponse<T> success(String message, T data) {
        return new ChatResponse<>(true, message, data, null);
    }

    public static <T> ChatResponse<T> error(String errorCode, String message) {
        return new ChatResponse<>(false, message, null, errorCode);
    }
}