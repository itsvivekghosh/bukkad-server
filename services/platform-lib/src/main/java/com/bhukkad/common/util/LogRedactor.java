package com.bhukkad.common.util;

/**
 * Log-line redaction helpers for PII that must never appear in raw form in
 * aggregated logs (audit V-21: {@code TwilioSmsSender} logged full phone
 * numbers). Pure functions — no logging framework dependency, so every
 * service can use them without pulling platform web infrastructure.
 */
public final class LogRedactor {

    private LogRedactor() {
    }

    /**
     * Masks an E.164-ish phone number keeping the first two and last two
     * characters visible: {@code +919812345678 -> +9*********78}.
     * Anything shorter than 5 characters is fully masked (too little
     * structure to safely keep any), {@code null} stays {@code null}.
     */
    public static String maskE164(String phone) {
        if (phone == null) {
            return null;
        }
        if (phone.length() < 5) {
            return "*".repeat(phone.length());
        }
        return phone.substring(0, 2) + "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 2);
    }

    /**
     * Masks an email's local part keeping 1 visible character, and the domain
     * with only its TLD visible: {@code vivek.ghosh@example.com ->
     * v***@e*******m}. Blank input is returned as-is (nothing to leak).
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // Not an email shape: full mask is the safe default.
            return "*".repeat(email.length());
        }
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        return local.charAt(0)
                + "*".repeat(Math.max(1, local.length() - 1))
                + "@"
                + maskDomain(domain);
    }

    private static String maskDomain(String domain) {
        if (domain.length() < 3) {
            return "*".repeat(domain.length());
        }
        return domain.charAt(0)
                + "*".repeat(domain.length() - 2)
                + domain.charAt(domain.length() - 1);
    }
}
