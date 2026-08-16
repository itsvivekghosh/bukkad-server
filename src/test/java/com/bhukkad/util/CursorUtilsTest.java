package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class CursorUtilsTest {

    @Test
    void encodeDecode_roundTripsCursor() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 3, 14, 10, 15, 30);
        String cursor = CursorUtils.encode(createdAt, 42L);

        CursorUtils.OrderCursor decoded = CursorUtils.decode(cursor).orElseThrow();

        assertEquals(createdAt, decoded.createdAt());
        assertEquals(42L, decoded.id());
    }

    @Test
    void decode_blankReturnsEmpty() {
        assertTrue(CursorUtils.decode(null).isEmpty());
        assertTrue(CursorUtils.decode("").isEmpty());
    }

    @Test
    void decode_invalidReturnsEmpty() {
        assertTrue(CursorUtils.decode("not-a-cursor").isEmpty());
    }
}
