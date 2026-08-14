package com.bhukkad.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppLoggerTest {

    private final AppLogger logger = AppLogger.getLogger(AppLoggerTest.class);

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void debugMethods_logWhenDebugEnabled() {
        ch.qos.logback.classic.Logger logback =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AppLoggerTest.class);
        logback.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logger.debug("debug");
        logger.debug("debug {}", "arg");
        logback.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @Test
    void allLogMethods_executeWithoutError() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("orderId", 1);
        context.put("status", "OK");

        assertDoesNotThrow(() -> {
            logger.info("info");
            logger.info("info {}", "arg");
            logger.logEvent("EVENT", "message");
            logger.logEvent("EVENT", "message", context);
            logger.logEvent("EVENT", "message", null);
            logger.logEvent("EVENT", "message", Map.of());
            logger.debug("debug");
            logger.debug("debug {}", "arg");
            logger.warn("warn");
            logger.warn("warn {}", "arg");
            logger.warnWithContext("warn", context);
            logger.warnWithContext("warn", null);
            logger.warnWithContext("warn", Map.of());
            logger.error("error");
            logger.error("error", new RuntimeException("boom"));
            logger.error("error {}", "arg");
            logger.errorWithContext("error", new RuntimeException("boom"), context);
            logger.errorWithContext("error", new RuntimeException("boom"), null);
            logger.errorWithContext("error", new RuntimeException("boom"), Map.of());
            logger.logPerformance("fast", 100);
            logger.logPerformance("slow", 1500);
            logger.logSecurityEvent("LOGIN", "ok");
            logger.logSecurityWarning("LOGIN", "bad");
            logger.logOrderEvent("CREATED", 1L, "details");
            logger.logPaymentEvent("PAID", 1L, 99.0, "details");
        });
    }

    @Test
    void mdcHelpers_putAndClear() {
        logger.addContext("traceId", "abc");
        assertEquals("abc", MDC.get("traceId"));
        logger.clearContext();
        assertNull(MDC.get("traceId"));
    }
}
