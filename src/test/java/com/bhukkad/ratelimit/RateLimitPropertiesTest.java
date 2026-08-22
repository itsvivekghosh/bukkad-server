package com.bhukkad.ratelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateLimitPropertiesTest {

    @Test
    void effectiveLimit_appliesTierMultiplier() {
        RateLimitProperties props = new RateLimitProperties();
        props.setBuckets(java.util.Map.of("search", new RateLimitProperties.Bucket(60, 60)));

        assertEquals(60, props.effectiveLimit("search", "free"));
        assertEquals(120, props.effectiveLimit("search", "premium"));
        assertEquals(180, props.effectiveLimit("search", "gold"));
        assertEquals(240, props.effectiveLimit("search", "platinum"));
    }

    @Test
    void effectiveLimit_unknownTierFallsBackToOne() {
        RateLimitProperties props = new RateLimitProperties();
        props.setBuckets(java.util.Map.of("search", new RateLimitProperties.Bucket(60, 60)));

        assertEquals(60, props.effectiveLimit("search", "unknown"));
        assertEquals(60, props.effectiveLimit("search", null));
    }

    @Test
    void effectiveLimit_roundsUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setBuckets(java.util.Map.of("search", new RateLimitProperties.Bucket(5, 60)));

        assertEquals(10, props.effectiveLimit("search", "premium"));
    }
}
