package com.bhukkad.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MDCContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void create_generatesTraceIdAndSupportsFluentContext() {
        try (MDCContext context = MDCContext.create()
                .withUserId("1")
                .withUserEmail("a@b.com")
                .withUserRole("CUSTOMER")
                .withRequestPath("/api")
                .withRequestMethod("GET")
                .withIpAddress("127.0.0.1")
                .withSessionId("sid")) {
            assertNotNull(context.getTraceId());
            assertEquals(16, context.getTraceId().length());
            assertEquals(context.getTraceId(), MDC.get(LoggingConstants.TRACE_ID));
            assertEquals("1", MDC.get(LoggingConstants.USER_ID));
            assertEquals("a@b.com", MDC.get(LoggingConstants.USER_EMAIL));
            assertEquals("CUSTOMER", MDC.get(LoggingConstants.USER_ROLE));
            assertEquals("/api", MDC.get(LoggingConstants.REQUEST_PATH));
            assertEquals("GET", MDC.get(LoggingConstants.REQUEST_METHOD));
            assertEquals("127.0.0.1", MDC.get(LoggingConstants.IP_ADDRESS));
            assertEquals("sid", MDC.get(LoggingConstants.SESSION_ID));
        }
        assertNull(MDC.get(LoggingConstants.TRACE_ID));
    }

    @Test
    void create_withProvidedTraceId() {
        try (MDCContext context = MDCContext.create("custom-trace")) {
            assertEquals("custom-trace", context.getTraceId());
            assertEquals("custom-trace", MDC.get(LoggingConstants.TRACE_ID));
        }
    }
}
