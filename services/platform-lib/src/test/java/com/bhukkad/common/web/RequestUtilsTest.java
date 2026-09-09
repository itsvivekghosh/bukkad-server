package com.bhukkad.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestUtilsTest {

    @Test
    void extractTokenFromRequestHeaders_returnsToken() {
        assertEquals("my-token", RequestUtils.extractTokenFromRequestHeaders("Bearer my-token"));
    }

    @Test
    void extractTokenFromRequestHeaders_throwsWhenNull() {
        assertThrows(RuntimeException.class, () -> RequestUtils.extractTokenFromRequestHeaders(null));
    }

    @Test
    void extractTokenFromRequestHeaders_throwsWhenNotBearer() {
        assertThrows(RuntimeException.class, () -> RequestUtils.extractTokenFromRequestHeaders("Basic abc"));
    }

    @Test
    void resolveClientIp_returnsUnknownWhenNoRequest() {
        assertEquals("unknown", RequestUtils.resolveClientIp());
    }

    @Test
    void resolveClientIp_usesXForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("203.0.113.1", RequestUtils.resolveClientIp());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void resolveClientIp_usesRemoteAddrAsFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("192.168.1.1", RequestUtils.resolveClientIp());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void resolveDeviceFingerprint_returnsNullWhenNoRequest() {
        assertNull(RequestUtils.resolveDeviceFingerprint());
    }

    @Test
    void resolveDeviceFingerprint_returnsHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Device-Fingerprint", "fp-abc-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            assertEquals("fp-abc-123", RequestUtils.resolveDeviceFingerprint());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void normalizeFingerprint_returnsNullWhenBlank() {
        assertNull(RequestUtils.normalizeFingerprint(null));
        assertNull(RequestUtils.normalizeFingerprint(""));
        assertNull(RequestUtils.normalizeFingerprint("   "));
    }

    @Test
    void normalizeFingerprint_trimsAndCaps() {
        String result = RequestUtils.normalizeFingerprint("  fp-long  ");
        assertEquals("fp-long", result);
    }
}