package com.bhukkad.engagement.chat.ai;

import java.util.regex.Pattern;

/**
 * PII (Personally Identifiable Information) redaction utility.
 * Reuses the masking logic from {@link com.bhukkad.common.logging.PiiMaskingConverter}.
 */
public final class PiiRedactor {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)\\d{10}(?!\\d)");

    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,16}\\b");

    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    private PiiRedactor() {}

    /**
     * Redacts PII from the input text.
     * Masks emails, phone numbers, credit card numbers, and SSNs.
     *
     * @param input the text to redact
     * @return text with PII masked
     */
    public static String redact(String input) {
        if (input == null) {
            return null;
        }
        String masked = EMAIL_PATTERN.matcher(input).replaceAll("***@***");
        masked = PHONE_PATTERN.matcher(masked).replaceAll("**********");
        masked = CREDIT_CARD_PATTERN.matcher(masked).replaceAll("**** **** **** ****");
        masked = SSN_PATTERN.matcher(masked).replaceAll("***-**-****");
        return masked;
    }

    /**
     * Redacts PII from the input text, preserving the original for logging.
     *
     * @param input the text to redact
     * @return pair of (redacted, original) - original is null if no PII found
     */
    public static RedactionResult redactWithOriginal(String input) {
        if (input == null) {
            return new RedactionResult(null, null);
        }
        String redacted = redact(input);
        boolean hasPii = !redacted.equals(input);
        return new RedactionResult(redacted, hasPii ? input : null);
    }

    /**
     * Result of PII redaction with original preserved for audit.
     */
    public record RedactionResult(String redacted, String original) {}
}