package com.bhukkad.logging;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class LogSanitizerTest {

    @Test
    void sanitizeHeaderValue_nulls() {
        assertEquals("null", LogSanitizer.sanitizeHeaderValue(null, "v"));
        assertEquals("null", LogSanitizer.sanitizeHeaderValue("Authorization", null));
        assertEquals("null", LogSanitizer.sanitizeHeaderValue(null, null));
    }

    @Test
    void sanitizeHeaderValue_sensitiveMasked() {
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("cookie", "sid=abc"));
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("Set-Cookie", "sid=abc"));
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("x-api-key", "k"));
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("X-Auth-Token", "t"));
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("proxy-authorization", "p"));
        assertEquals("***MASKED***", LogSanitizer.sanitizeHeaderValue("Authorization", "Bearer short"));
    }

    @Test
    void sanitizeHeaderValue_bearerMaskedWhenLong() {
        String value = "Bearer 1234567890abcdefghij";
        String sanitized = LogSanitizer.sanitizeHeaderValue("authorization", value);

        assertTrue(sanitized.startsWith("Bearer 1234567890"));
        assertTrue(sanitized.contains("..."));
        assertTrue(sanitized.endsWith("fghij"));
    }

    @Test
    void sanitizeHeaderValue_bearerCaseInsensitive() {
        String value = "bearer 1234567890abcdefghij";
        String sanitized = LogSanitizer.sanitizeHeaderValue("AUTHORIZATION", value);
        assertTrue(sanitized.startsWith("Bearer "));
    }

    @Test
    void sanitizeHeaderValue_truncatedWhenLong() {
        String longValue = "x".repeat(201);
        String sanitized = LogSanitizer.sanitizeHeaderValue("Accept", longValue);
        assertEquals("x".repeat(200) + "...TRUNCATED", sanitized);
    }

    @Test
    void sanitizeHeaderValue_plainShortValue() {
        assertEquals("application/json", LogSanitizer.sanitizeHeaderValue("Content-Type", "application/json"));
    }

    @Test
    void sanitizeBody_emptyAndNull() {
        assertEquals("[empty]", LogSanitizer.sanitizeBody(null));
        assertEquals("[empty]", LogSanitizer.sanitizeBody(""));
    }

    @Test
    void sanitizeBody_masksSensitiveFields() {
        String body = "{\"password\":\"secret\",\"token\":\"abc\",\"name\":\"bob\"}";
        String sanitized = LogSanitizer.sanitizeBody(body);
        assertTrue(sanitized.contains("***MASKED***"));
        assertTrue(sanitized.contains("bob"));
        assertFalse(sanitized.contains("secret"));
    }

    @Test
    void sanitizeBody_masksFieldWithSpaceAfterColon() {
        String sanitized = LogSanitizer.sanitizeBody("{\"otp\": \"123456\"}");
        assertTrue(sanitized.contains("***MASKED***"));
    }

    @Test
    void sanitizeBody_truncatesWhenLongerThan2000() {
        String body = "a".repeat(2001);
        String sanitized = LogSanitizer.sanitizeBody(body);
        assertTrue(sanitized.contains("...TRUNCATED(total:2001 chars)"));
    }

    @Test
    void sanitizeBody_collapsesWhitespace() {
        assertEquals("{ \"name\": \"a\" }", LogSanitizer.sanitizeBody("{\n  \"name\": \"a\"\n}"));
    }

    @Test
    void isLoggableContentType() {
        assertFalse(LogSanitizer.isLoggableContentType(null));
        assertTrue(LogSanitizer.isLoggableContentType("application/json"));
        assertTrue(LogSanitizer.isLoggableContentType("APPLICATION/XML"));
        assertTrue(LogSanitizer.isLoggableContentType("text/plain"));
        assertTrue(LogSanitizer.isLoggableContentType("application/x-www-form-urlencoded"));
        assertFalse(LogSanitizer.isLoggableContentType("image/png"));
    }

    @Test
    void isBinaryContent() {
        assertFalse(LogSanitizer.isBinaryContent(null));
        assertTrue(LogSanitizer.isBinaryContent("image/png"));
        assertTrue(LogSanitizer.isBinaryContent("video/mp4"));
        assertTrue(LogSanitizer.isBinaryContent("audio/mpeg"));
        assertTrue(LogSanitizer.isBinaryContent("application/octet-stream"));
        assertTrue(LogSanitizer.isBinaryContent("application/pdf"));
        assertTrue(LogSanitizer.isBinaryContent("application/zip"));
        assertFalse(LogSanitizer.isBinaryContent("application/json"));
    }

    @Test
    void privateConstructor() throws Exception {
        Constructor<LogSanitizer> ctor = LogSanitizer.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }
}
