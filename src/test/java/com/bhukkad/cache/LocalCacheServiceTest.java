package com.bhukkad.cache;

import com.bhukkad.config.LocalCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LocalCacheServiceTest {

    private LocalCacheService service;

    private LocalCacheService build(boolean enabled, int ttlSeconds) {
        LocalCacheProperties props = new LocalCacheProperties();
        props.setEnabled(enabled);
        props.setMaxSize(100);
        props.setTtlSeconds(ttlSeconds);
        return new LocalCacheService(props, new com.bhukkad.cache.StampedeProperties());
    }

    @BeforeEach
    void setUp() {
        service = build(true, 60);
    }

    @Test void putAndGet_roundTripsValue() {
        service.put("k1", "v1");
        Optional<String> got = service.get("k1", String.class);
        assertTrue(got.isPresent());
        assertEquals("v1", got.get());
    }

    @Test void get_missingKey_returnsEmptyAndCountsMiss() {
        assertTrue(service.get("nope", String.class).isEmpty());
        assertEquals(1L, service.getStats().get("misses"));
    }

    @Test void get_wrongType_returnsEmpty() {
        service.put("k", 42);
        assertTrue(service.get("k", String.class).isEmpty());
    }

    @Test void put_nullValue_isIgnored() {
        service.put("k", null);
        assertTrue(service.get("k", String.class).isEmpty());
    }
    @Test
    void invalidate_removesValue() {
        service.put("k", "v");
        service.invalidate("k");
        assertTrue(service.get("k", String.class).isEmpty());
    }

    @Test
    void clearAll_evictsEveryValue() {
        service.put("k1", "v1");
        service.put("k2", "v2");
        service.clearAll();
        assertTrue(service.get("k1", String.class).isEmpty());
        assertTrue(service.get("k2", String.class).isEmpty());
    }

    @Test
    void clearAll_disabledCache_isNoOp() {
        LocalCacheService disabled = build(false, 60);
        disabled.put("k1", "v1");
        assertDoesNotThrow(disabled::clearAll);
    }

    @Test void get_hit_incrementsHits() {
        service.put("k", "v");
        service.get("k", String.class);
        assertEquals(1L, service.getStats().get("hits"));
    }

    @Test void disabled_returnsEmptyAndDoesNotStore() {
        LocalCacheService disabled = build(false, 60);
        assertFalse(disabled.isEnabled());
        disabled.put("k", "v");
        assertTrue(disabled.get("k", String.class).isEmpty());
        assertFalse((Boolean) disabled.getStats().get("enabled"));
    }

    @Test void getStats_reportsSizeAndHitRate() {
        service.put("a", 1);
        service.put("b", 2);
        service.get("a", Integer.class);
        Map<String, Object> stats = service.getStats();
        assertTrue((Long) stats.get("estimatedSize") >= 2);
        assertTrue((Double) stats.get("caffeineHitRate") >= 0.0);
    }

    @Test void expiredValue_isNotReturned() throws InterruptedException {
        LocalCacheService shortTtl = build(true, 1);
        shortTtl.put("k", "v");
        Thread.sleep(1100);
        assertTrue(shortTtl.get("k", String.class).isEmpty());
    }

    @Test void probabilisticEarlyExpiration_treatsNearExpiryEntryAsMiss() throws Exception {
        // Enable stampede protection with a 100% early-expiration window so an
        // entry whose deadline is ~1ms away is (with overwhelming probability)
        // refreshed instead of served stale.
        LocalCacheProperties props = new LocalCacheProperties();
        props.setEnabled(true);
        props.setMaxSize(100);
        props.setTtlSeconds(60);
        com.bhukkad.cache.StampedeProperties stampede = new com.bhukkad.cache.StampedeProperties();
        stampede.setEnabled(true);
        stampede.setJitterPercent(0);
        stampede.setEarlyExpirePercent(100);
        LocalCacheService early = new LocalCacheService(props, stampede);

        early.put("k", "v", 60); // stores value + deadline

        // Force the deadline to ~1ms from now so remaining << early window.
        java.lang.reflect.Field deadlines =
                LocalCacheService.class.getDeclaredField("ttlDeadlines");
        deadlines.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> map =
                (java.util.Map<String, Long>) deadlines.get(early);
        map.put("k", System.currentTimeMillis() + 1);

        // The entry is still physically present, but probabilistically expired.
        assertTrue(early.get("k", String.class).isEmpty());
    }

    @Test void probabilisticEarlyExpiration_disabled_returnsCachedValue() {
        // Stampede disabled -> early-expiration never applies, value is served.
        LocalCacheProperties props = new LocalCacheProperties();
        props.setEnabled(true);
        props.setMaxSize(100);
        props.setTtlSeconds(60);
        com.bhukkad.cache.StampedeProperties stampede = new com.bhukkad.cache.StampedeProperties();
        stampede.setEnabled(false);
        stampede.setEarlyExpirePercent(5);
        LocalCacheService plain = new LocalCacheService(props, stampede);

        plain.put("k", "v", 60);
        assertTrue(plain.get("k", String.class).isPresent());
    }
}
