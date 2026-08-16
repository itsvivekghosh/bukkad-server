package com.bhukkad.logging;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TraceIdResolverTest {

    @Test
    void resolveTraceId_usesInboundHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "abcd1234abcd1234");

        assertEquals("abcd1234abcd1234", TraceIdResolver.resolveTraceId(request));
    }

    @Test
    void resolveTraceId_parsesTraceparent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        assertEquals("4bf92f3577b34da6", TraceIdResolver.resolveTraceId(request));
    }

    @Test
    void resolveRequestId_usesInboundHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req12345");

        assertEquals("req12345", TraceIdResolver.resolveRequestId(request));
    }

    @Test
    void resolveTraceId_generatesWhenMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String first = TraceIdResolver.resolveTraceId(request);
        String second = TraceIdResolver.resolveTraceId(request);

        assertEquals(16, first.length());
        assertNotEquals(first, second);
    }
}
