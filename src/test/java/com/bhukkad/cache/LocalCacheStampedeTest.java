package com.bhukkad.cache;

import com.bhukkad.config.LocalCacheProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCacheStampedeTest {

    private LocalCacheService service;

    private LocalCacheService build(boolean stampedeEnabled, int jitterPercent) {
        LocalCacheProperties props = new LocalCacheProperties();
        props.setEnabled(true);
        props.setMaxSize(100);
        props.setTtlSeconds(60);
        StampedeProperties stampede = new StampedeProperties();
        stampede.setEnabled(stampedeEnabled);
        stampede.setJitterPercent(jitterPercent);
        return new LocalCacheService(props, stampede);
    }

    @BeforeEach
    void setUp() {
        service = build(true, 10);
    }

    @Test
    void getOrCompute_storesComputedValue() {
        String result = service.getOrCompute("k", String.class, 60, () -> "v");

        assertEquals("v", result);
        assertEquals(Optional.of("v"), service.get("k", String.class));
    }

    @Test
    void getOrCompute_returnsCachedValue_withoutInvokingSupplier() {
        service.put("k", "cached");
        AtomicInteger calls = new AtomicInteger();

        String result = service.getOrCompute("k", String.class, 60, () -> {
            calls.incrementAndGet();
            return "computed";
        });

        assertEquals("cached", result);
        assertEquals(0, calls.get());
    }

    @Test
    void getOrCompute_singleFlight_computesOnceUnderConcurrency() throws Exception {
        AtomicInteger computations = new AtomicInteger();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service.getOrCompute("hot", String.class, 60, () -> {
                        computations.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return "computed";
                    });
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<String> future : futures) {
                assertEquals("computed", future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, computations.get(), "cold key must be computed exactly once");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void getOrCompute_supplierFailure_propagatesAndAllowsRetry() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> service.getOrCompute("k", String.class, 60, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, calls.get());

        String result = service.getOrCompute("k", String.class, 60, () -> "recovered");
        assertEquals("recovered", result);
    }

    @Test
    void getOrCompute_whenDisabled_computesEveryMiss() {
        LocalCacheService plain = build(false, 10);
        AtomicInteger calls = new AtomicInteger();

        plain.getOrCompute("k", String.class, 60, () -> {
            calls.incrementAndGet();
            return "a";
        });
        plain.invalidate("k");
        plain.getOrCompute("k", String.class, 60, () -> {
            calls.incrementAndGet();
            return "b";
        });

        assertEquals(2, calls.get());
    }

    @Test
    void put_withTtl_appliesJitterWithinBounds() throws Exception {
        long ttl = 100;
        long before = System.currentTimeMillis();
        service.put("k", "v", ttl);
        Long deadline = readDeadline(service, "k");

        assertNotNull(deadline);
        long actualMs = deadline - before;
        // jitterPercent 10 => ttl between 90s and 110s
        assertTrue(actualMs >= 90_000L, "jittered ttl too short: " + actualMs);
        assertTrue(actualMs <= 110_000L, "jittered ttl too long: " + actualMs);
    }

    @Test
    void put_withTtl_noJitterWhenStampedeDisabled() throws Exception {
        LocalCacheService plain = build(false, 10);
        long ttl = 100;
        long before = System.currentTimeMillis();
        plain.put("k", "v", ttl);
        Long deadline = readDeadline(plain, "k");

        assertNotNull(deadline);
        long actualMs = deadline - before;
        // stampede disabled => exact ttl, allow a few ms of clock skew
        assertTrue(Math.abs(actualMs - 100_000L) <= 2_000L, "expected exact ttl, got " + actualMs);
    }

    @Test
    void put_withShortTtl_expiresAfterJitter() throws InterruptedException {
        service.put("k", "v", 1);
        Thread.sleep(1300);

        assertTrue(service.get("k", String.class).isEmpty());
    }

    @Test
    void invalidate_clearsDeadlineAndEntry() throws Exception {
        service.put("k", "v", 60);
        service.invalidate("k");

        assertNull(readDeadline(service, "k"));
        assertTrue(service.get("k", String.class).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Long readDeadline(LocalCacheService target, String key) throws Exception {
        Field field = LocalCacheService.class.getDeclaredField("ttlDeadlines");
        field.setAccessible(true);
        return ((Map<String, Long>) field.get(target)).get(key);
    }
}