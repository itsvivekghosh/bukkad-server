package com.bhukkad.dto.response;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseTest {

    @Test
    void success_withDataOnly() {
        ApiResponse<String> response = ApiResponse.success("payload");

        assertTrue(response.isSuccess());
        assertEquals("payload", response.getData());
        assertNull(response.getMessage());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void success_withMessageAndData() {
        ApiResponse<Integer> response = ApiResponse.success("created", 1);

        assertTrue(response.isSuccess());
        assertEquals("created", response.getMessage());
        assertEquals(1, response.getData());
        assertNotNull(response.getTimestamp());
    }

    @Test
    void error_setsFailureMessage() {
        ApiResponse<Void> response = ApiResponse.error("failed");

        assertFalse(response.isSuccess());
        assertEquals("failed", response.getMessage());
        assertNull(response.getData());
        assertNotNull(response.getTimestamp());
    }
}
