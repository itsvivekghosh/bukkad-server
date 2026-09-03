package com.bhukkad.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link CacheInvalidatedEvent} POJO, including the
 * Jackson round-trip it relies on for Redis pub/sub transport (see class
 * Javadoc: no-arg constructor plus getters/setters are part of the contract).
 */
class CacheInvalidatedEventTest {

    @Test
    void noArgConstructor_leavesStringsNullAndPatternFalse() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent();

        assertNull(event.getCacheName());
        assertNull(event.getKey());
        assertFalse(event.isPattern());
    }

    @Test
    void allArgsConstructor_patternTrue_storesAllValues() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("menus", "menus:*", true);

        assertEquals("menus", event.getCacheName());
        assertEquals("menus:*", event.getKey());
        assertTrue(event.isPattern());
    }

    @Test
    void allArgsConstructor_patternFalse_isNotAPattern() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant:7", false);

        assertEquals("restaurant", event.getCacheName());
        assertEquals("restaurant:7", event.getKey());
        assertFalse(event.isPattern());
    }

    @Test
    void setCacheName_updatesValue() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent();

        event.setCacheName("orders");

        assertEquals("orders", event.getCacheName());
    }

    @Test
    void setKey_updatesValue() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent();

        event.setKey("orders:99");

        assertEquals("orders:99", event.getKey());
    }

    @Test
    void setPattern_togglesFlagBothWays() {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent();

        event.setPattern(true);
        assertTrue(event.isPattern());

        event.setPattern(false);
        assertFalse(event.isPattern());
    }

    @Test
    void jacksonRoundTrip_preservesAllFields() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        CacheInvalidatedEvent original = new CacheInvalidatedEvent("menu", "bhukkad:menu:42", true);

        String json = mapper.writeValueAsString(original);
        CacheInvalidatedEvent deserialized = mapper.readValue(json, CacheInvalidatedEvent.class);

        assertEquals("menu", deserialized.getCacheName());
        assertEquals("bhukkad:menu:42", deserialized.getKey());
        assertTrue(deserialized.isPattern());

        CacheInvalidatedEvent keyEvent =
                mapper.readValue(mapper.writeValueAsString(new CacheInvalidatedEvent("m", "k", false)),
                        CacheInvalidatedEvent.class);
        assertFalse(keyEvent.isPattern());
    }
}
