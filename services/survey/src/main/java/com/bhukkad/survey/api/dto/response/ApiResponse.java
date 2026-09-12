package com.bhukkad.survey.api.dto.response;

/**
 * Standard API response envelope for the survey service, matching the
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