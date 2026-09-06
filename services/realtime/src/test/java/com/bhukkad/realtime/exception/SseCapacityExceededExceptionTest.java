package com.bhukkad.realtime.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SseCapacityExceededExceptionTest {

    @Test
    void messageOnly() {
        SseCapacityExceededException ex = new SseCapacityExceededException("Connection limit reached");

        assertEquals("Connection limit reached", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    void messageAndCause() {
        RuntimeException cause = new RuntimeException("Redis connection failed");
        SseCapacityExceededException ex = new SseCapacityExceededException("Connection limit reached");
        assertEquals("Connection limit reached", ex.getMessage());
    }
}
