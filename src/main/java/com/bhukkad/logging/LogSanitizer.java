package com.bhukkad.logging;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogSanitizer {

    // Sensitive headers that should be masked
    private static final Set<String> SENSITIVE_HEADERS = new HashSet<>(Arrays.asList(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "x-auth-token",
            "proxy-authorization"
    ));

    // Sensitive body fields that should be masked
    private static final Set<String> SENSITIVE_FIELDS = new HashSet<>(Arrays.asList(
            "password",
            "newPassword",
            "oldPassword",
            "confirmPassword",
            "token",
            "refreshToken",
            "secret",
            "secretKey",
            "apiKey",
            "creditCard",
            "cardNumber",
            "cvv",
            "ssn",
            "otp",
            "pin",
            "mfaToken",
            "authorization"
    ));

    // Query param keys that must be masked (lowercase comparison)
    private static final Set<String> SENSITIVE_QUERY_PARAMS = new HashSet<>(Arrays.asList(
            "password", "newpassword", "oldpassword", "confirmpassword",
            "token", "refreshtoken", "secret", "apikey", "otp", "pin",
            "mfatoken", "code", "authorization", "access_token", "refresh_token"
    ));

    private static final int MAX_BODY_LENGTH = 2000;
    private static final int MAX_HEADER_VALUE_LENGTH = 200;

    private LogSanitizer() {}

    /**
     * Mask sensitive header values
     */
    public static String sanitizeHeaderValue(String headerName, String headerValue) {
        if (headerName == null || headerValue == null) return "null";

        if (SENSITIVE_HEADERS.contains(headerName.toLowerCase())) {
            if (headerValue.toLowerCase().startsWith("bearer ") && headerValue.length() > 20) {
                // Show first 10 and last 5 chars of token
                return "Bearer " + headerValue.substring(7, 17) + "..." +
                        headerValue.substring(headerValue.length() - 5);
            }
            return "***MASKED***";
        }

        if (headerValue.length() > MAX_HEADER_VALUE_LENGTH) {
            return headerValue.substring(0, MAX_HEADER_VALUE_LENGTH) + "...TRUNCATED";
        }

        return headerValue;
    }

    /**
     * Mask sensitive fields in request/response body
     */
    public static String sanitizeBody(String body) {
        if (body == null || body.isEmpty()) return "[empty]";

        String sanitized = body;

        // Mask sensitive JSON fields
        for (String field : SENSITIVE_FIELDS) {
            // Pattern: "fieldName":"value" or "fieldName": "value"
            Pattern pattern = Pattern.compile(
                    "(\"" + field + "\"\\s*:\\s*\")(.*?)(\")",
                    Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = pattern.matcher(sanitized);
            sanitized = matcher.replaceAll("$1***MASKED***$3");
        }

        // Truncate if too long
        if (sanitized.length() > MAX_BODY_LENGTH) {
            sanitized = sanitized.substring(0, MAX_BODY_LENGTH) + "...TRUNCATED(total:" + body.length() + " chars)";
        }

        // Remove newlines and extra spaces for single-line logging
        sanitized = sanitized.replaceAll("\\s+", " ").trim();

        return sanitized;
    }

    /**
     * Check if content type is loggable
     */
    public static boolean isLoggableContentType(String contentType) {
        if (contentType == null) return false;

        String ct = contentType.toLowerCase();
        return ct.contains("json")
                || ct.contains("xml")
                || ct.contains("text")
                || ct.contains("form-urlencoded");
    }

    /**
     * Sanitize a single query-param value based on its key.
     */
    public static String sanitizeQueryParam(String key, String value) {
        if (key == null || value == null) return value;
        if (SENSITIVE_QUERY_PARAMS.contains(key.toLowerCase())) {
            return "***MASKED***";
        }
        // Extra safety: if value looks like JWT (three base64 segments)
        if (value.length() > 20 && value.chars().filter(ch -> ch == '.').count() == 2) {
            return "***JWT_MASKED***";
        }
        if (value.length() > MAX_HEADER_VALUE_LENGTH) {
            return value.substring(0, MAX_HEADER_VALUE_LENGTH) + "...TRUNCATED";
        }
        return value;
    }

    /**
     * Check if content type is binary (should not be logged)
     */
    public static boolean isBinaryContent(String contentType) {
        if (contentType == null) return false;

        String ct = contentType.toLowerCase();
        return ct.contains("image")
                || ct.contains("video")
                || ct.contains("audio")
                || ct.contains("octet-stream")
                || ct.contains("pdf")
                || ct.contains("zip");
    }
}