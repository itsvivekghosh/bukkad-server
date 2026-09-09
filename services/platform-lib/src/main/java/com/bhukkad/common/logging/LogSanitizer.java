package com.bhukkad.common.logging;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Redacts secrets/PII before they reach log output (port of
 * {@code com.bhukkad.logging.LogSanitizer}). Applied at the logging boundary —
 * never log raw tokens, passwords or card numbers.
 */
public final class LogSanitizer {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)(password|passwd|pwd|token|secret|authorization|api[_-]?key)\\s*[=:]\\s*\\S+",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\\b")
    );

    private static final String REDACTED = "[REDACTED]";

    private LogSanitizer() {
    }

    public static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String result = message;
        for (Pattern pattern : PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED);
        }
        return result;
    }
}
