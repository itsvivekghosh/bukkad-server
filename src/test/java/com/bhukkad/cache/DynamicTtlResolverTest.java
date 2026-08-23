package com.bhukkad.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicTtlResolverTest {

    @Test
    void resolve_returnsBaseTtlUnchangedWhenDisabled() {
        DynamicTtlResolver resolver = new DynamicTtlResolver(false, "11:00-14:00", 0.5, 1.5);
        assertEquals(60, resolver.resolve(60));
    }

    @Test
    void resolve_appliesPeakMultiplierWhenWindowCoversAllHours() {
        // "00:00-23:59" covers every possible LocalTime.now() → always peak.
        DynamicTtlResolver resolver = new DynamicTtlResolver(true, "00:00-23:59", 0.5, 1.5);
        assertEquals(30, resolver.resolve(60));
    }

    @Test
    void resolve_appliesOffPeakMultiplierWhenNoWindowsConfigured() {
        DynamicTtlResolver resolver = new DynamicTtlResolver(true, "", 0.5, 1.5);
        assertEquals(90, resolver.resolve(60));
    }

    @Test
    void resolve_neverReturnsLessThanOneSecond() {
        // 60 × 0.01 rounds to 1 after the floor; 5 × 0.01 would round to 0 → clamped.
        DynamicTtlResolver resolver = new DynamicTtlResolver(true, "00:00-23:59", 0.01, 0.01);
        assertTrue(resolver.resolve(5) >= 1);
    }

    @Test
    void resolve_handlesZeroBaseGracefully() {
        DynamicTtlResolver resolver = new DynamicTtlResolver(true, "00:00-23:59", 0.5, 1.5);
        assertEquals(0, resolver.resolve(0));
    }

    @Test
    void constructor_ignoresMalformedAndImpossibleWindows() {
        // "garbage" and "25:00-99:00" must be skipped without breaking startup;
        // only the valid window remains, making everything peak again.
        DynamicTtlResolver resolver = new DynamicTtlResolver(
                true, "garbage,25:00-99:00,11:00-14:00", 0.5, 1.5);
        // Off-peak for most of the day; just assert it produces a sane value.
        long ttl = resolver.resolve(60);
        assertTrue(ttl == 30 || ttl == 90);
    }
}
