package com.bhukkad.support.dto.response;

/**
 * Standard API response envelope for the support service, matching the
 * monolith's com.bhukkad.dto.response.ApiResponse contract.
 */
public record ApiResponse<T>(
        boolean success,
        String message,
        T data
) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }
}