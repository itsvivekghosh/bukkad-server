package com.bhukkad.dto.response;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNull;

class OrderSummaryResponseTest {

    @Test
    void constructor_nullStatus_leavesStatusNull() {
        OrderSummaryResponse response = new OrderSummaryResponse(
                1L, "ORD-1", 2L, "Ada", 3L, "Cafe",
                null, 10.0, "note",
                LocalDateTime.now(), null);

        assertNull(response.getStatus());
    }
}
