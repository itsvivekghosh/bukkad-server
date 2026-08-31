package com.bhukkad.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the logback-spring.xml configuration contains the
 * production-hardened settings that prevent disk-pressure:
 * <ul>
 *   <li>{@code totalSizeCap} — caps the combined size of rotated files</li>
 *   <li>{@code maxHistory} — hourly files retained for 7 days</li>
 * </ul>
 */
class LogbackConfigTest {

    private static final Path CONFIG = Paths.get("src/main/resources/logback-spring.xml");

    @Test
    void configFileExists() throws IOException {
        assertTrue(Files.exists(CONFIG), "logback-spring.xml must exist");
    }

    @Test
    void rollingPolicyHasTotalSizeCap() throws IOException {
        String content = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(content.contains("totalSizeCap"),
                "logback must configure totalSizeCap to bound disk usage");
        assertTrue(content.contains("TOTAL_SIZE_CAP"),
                "totalSizeCap must be driven by a property (TOTAL_SIZE_CAP)");
        assertTrue(content.contains("2GB"),
                "TOTAL_SIZE_CAP must default to 2GB");
    }

    @Test
    void rollingPolicyHasMaxHistory() throws IOException {
        String content = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(content.contains("MAX_HISTORY"),
                "maxHistory must be driven by a property (MAX_HISTORY)");
        assertTrue(content.contains("168"),
                "MAX_HISTORY must default to 168 (7 days × 24 hours)");
    }

    @Test
    void piiMaskingConverterRegistered() throws IOException {
        String content = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(content.contains("PiiMaskingConverter"),
                "PII masking converter must be registered");
    }

    @Test
    void asyncAppenderConfigured() throws IOException {
        String content = Files.readString(CONFIG, StandardCharsets.UTF_8);
        assertTrue(content.contains("ASYNC_SIFT"),
                "Async appender must be configured for non-blocking writes");
    }
}