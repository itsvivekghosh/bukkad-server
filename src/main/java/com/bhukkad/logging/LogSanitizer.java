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
            "pin"
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