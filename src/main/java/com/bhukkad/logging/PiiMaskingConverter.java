package com.bhukkad.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter that redacts PII from log messages before they are
 * written: email addresses become {@code ***@***} and bare 10-digit phone
 * numbers become {@code **********}. Registered in logback-spring.xml as the
 * {@code %pii} conversion word; replacing {@code %msg} with {@code %pii} in a
 * pattern masks every message emitted through that appender.
 */
public class PiiMaskingConverter extends MessageConverter {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)\\d{10}(?!\\d)");

    @Override
    public String convert(ILoggingEvent event) {
        String formatted = super.convert(event);
        return formatted == null ? null : mask(formatted);
    }

    /** Masks emails and 10-digit phone numbers in {@code input}. */
    public static String mask(String input) {
        if (input == null) {
            return null;
        }
        String masked = EMAIL_PATTERN.matcher(input).replaceAll("***@***");
        return PHONE_PATTERN.matcher(masked).replaceAll("**********");
    }
}