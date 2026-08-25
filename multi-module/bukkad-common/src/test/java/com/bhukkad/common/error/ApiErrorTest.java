package com.bhukkad.common.error;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiErrorTest {

    @Test
    void of_simple_returnsEnvelopeWithDefaults() {
        ApiError error = ApiError.of(ErrorCode.NOT_FOUND, "Order not found", "trace-1");

        assertEquals("NOT_FOUND", error.code());
        assertEquals("Order not found", error.message());
        assertEquals("trace-1", error.traceId());
        assertTrue(error.details().isEmpty());
        assertNotNull(error.timestamp());
    }

    @Test
    void of_withDetails_keepsContext() {
        ApiError error = ApiError.of(ErrorCode.ORDER_NOT_ELIGIBLE, "Not eligible",
                "trace-2", Map.of("orderId", "42"));

        assertEquals("ORDER_NOT_ELIGIBLE", error.code());
        assertEquals("42", error.details().get("orderId"));
    }

    @Test
    void allErrorCodes_haveNames() {
        for (ErrorCode code : ErrorCode.values()) {
            assertEquals(code.name(), code.name().toUpperCase());
        }
    }
}
