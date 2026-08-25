package com.bhukkad.cache;

import com.bhukkad.cache.invalidation.DistributedCacheInvalidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOps;
    @Mock
    private ValueOperations<String, String> stringValueOps;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private LocalCacheService localCacheService;
    @Mock
    private DistributedCacheInvalidator distributedInvalidator;
    @Mock
    private Cursor<String> cursor;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private RedisCacheService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOps);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        // Constructed explicitly (not @InjectMocks): StringRedisTemplate is a
        // subtype of RedisTemplate, which makes Mockito's constructor injection
        // ambiguous about which mock goes into which parameter.
        service = new RedisCacheService(
                redisTemplate, stringRedisTemplate, objectMapper, localCacheService, distributedInvalidator);
    }

    @Test
    void get_returnsCachedValue() {
        when(valueOps.get("bhukkad:test-key")).thenReturn("cached-value");
        when(objectMapper.convertValue("cached-value", String.class)).thenReturn("cached-value");

        Optional<String> result = service.get("test-key", String.class);
        assertTrue(result.isPresent());
        assertEquals("cached-value", result.get());
    }

    @Test
    void get_returnsEmptyWhenNull() {
        when(valueOps.get("bhukkad:missing-key")).thenReturn(null);

        Optional<String> result = service.get("missing-key", String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void get_returnsEmptyOnException() {
        when(valueOps.get("bhukkad:key")).thenThrow(new RuntimeException("Redis error"));

        Optional<String> result = service.get("key", String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void set_storesValueWithTTL() {
        service.set("test-key", "test-value", 300);
        verify(redisTemplate.opsForValue()).set(eq("bhukkad:test-key"), eq("test-value"), eq(Duration.ofSeconds(300)));
    }

    @Test
    void set_handlesException() {
        doThrow(new RuntimeException("Redis error")).when(valueOps).set(anyString(), any(), any(Duration.class));

        // Should not throw
        service.set("test-key", "test-value", 300);
    }

    @Test
    void exists_returnsTrueWhenKeyExists() {
        when(redisTemplate.hasKey("bhukkad:test-key")).thenReturn(true);
        assertTrue(service.exists("test-key"));
    }

    @Test
    void exists_returnsFalseWhenKeyMissing() {
        when(redisTemplate.hasKey("bhukkad:missing-key")).thenReturn(false);
        assertFalse(service.exists("missing-key"));
    }

    @Test
    void exists_returnsFalseOnException() {
        when(redisTemplate.hasKey("bhukkad:key")).thenThrow(new RuntimeException("Redis error"));
        assertFalse(service.exists("key"));
    }

    @Test
    void increment_incrementsValue() {
        when(valueOps.increment("bhukkad:counter")).thenReturn(5L);
        Long result = service.increment("counter");
        assertEquals(5L, result);
    }

    @Test
    void increment_returnsNullOnException() {
        when(valueOps.increment("bhukkad:counter")).thenThrow(new RuntimeException("Redis error"));
        Long result = service.increment("counter");
        assertNull(result);
    }

    @Test
    void delete_deletesKeyAndInvalidatesL1() {
        service.delete("test-key");
        verify(redisTemplate).delete("bhukkad:test-key");
        verify(localCacheService).invalidate("test-key");
    }

    @Test
    void deletePattern_scansAndDeletesKeys() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("bhukkad:pattern:1", "bhukkad:pattern:2");

        service.deletePattern("pattern");

        verify(redisTemplate).delete(Set.of("bhukkad:pattern:1", "bhukkad:pattern:2"));
    }

    @Test
    void deletePattern_handlesEmptyScan() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        service.deletePattern("pattern");

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void deletePattern_handlesException() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("Scan error"));
        // Should not throw
        service.deletePattern("pattern");
    }

    @Test
    void getList_returnsEmptyWhenNull() {
        when(valueOps.get("bhukkad:key")).thenReturn(null);

        Optional<List<String>> result = service.getList("key", String.class);

        assertFalse(result.isPresent());
    }

    @Test
    void getList_returnsEmptyOnException() {
        when(valueOps.get("bhukkad:key")).thenThrow(new RuntimeException("Redis error"));

        Optional<List<String>> result = service.getList("key", String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void setExpiry_setsExpiryOnKey() {
        service.setExpiry("test-key", 600);
        verify(redisTemplate).expire("bhukkad:test-key", 600, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void setExpiry_handlesException() {
        doThrow(new RuntimeException("Redis error")).when(redisTemplate).expire(anyString(), anyLong(), any());
        // Should not throw
        service.setExpiry("test-key", 600);
    }

    @Test
    void hSet_setsHashField() {
        service.hSet("key", "field", "value");
        verify(hashOps).put("bhukkad:key", "field", "value");
    }

    @Test
    void hSet_handlesException() {
        doThrow(new RuntimeException("Redis error")).when(hashOps).put(anyString(), any(), any());
        service.hSet("key", "field", "value");
    }

    @Test
    void hGet_returnsValue() {
        when(hashOps.get("bhukkad:key", "field")).thenReturn("value");
        when(objectMapper.convertValue("value", String.class)).thenReturn("value");

        Optional<String> result = service.hGet("key", "field", String.class);

        assertTrue(result.isPresent());
        assertEquals("value", result.get());
    }

    @Test
    void hGet_returnsEmptyWhenNull() {
        when(hashOps.get("bhukkad:key", "field")).thenReturn(null);

        Optional<String> result = service.hGet("key", "field", String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void hGet_returnsEmptyOnException() {
        when(hashOps.get("bhukkad:key", "field")).thenThrow(new RuntimeException("Redis error"));

        Optional<String> result = service.hGet("key", "field", String.class);
        assertFalse(result.isPresent());
    }

    @Test
    void hDelete_deletesHashFields() {
        service.hDelete("key", "field1", "field2");
        verify(hashOps).delete("bhukkad:key", "field1", "field2");
    }

    @Test
    void hDelete_handlesException() {
        doThrow(new RuntimeException("Redis error")).when(hashOps).delete(anyString(), any());
        service.hDelete("key", "field1");
    }

    @Test
    void clearAll_scansAndDeletesAllKeys() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("bhukkad:key1", "bhukkad:key2");

        service.clearAll();

        verify(redisTemplate).delete(Set.of("bhukkad:key1", "bhukkad:key2"));
    }

    @Test
    void clearAll_handlesEmptyScan() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        service.clearAll();

        verify(redisTemplate, never()).delete(any(Set.class));
    }

    @Test
    void clearAll_handlesException() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("Scan error"));
        service.clearAll();
    }

    @Test
    void getCacheStats_returnsStats() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("bhukkad:restaurant:1");
        when(localCacheService.getStats()).thenReturn(Map.of("size", 100));

        Map<String, Object> stats = service.getCacheStats();

        assertEquals(1, stats.get("totalKeys"));
        assertTrue(stats.containsKey("keysByType"));
        assertTrue(stats.containsKey("localCache"));
    }

    @Test
    void getCacheStats_handlesException() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);
        when(localCacheService.getStats()).thenReturn(Map.of("size", 100));

        Map<String, Object> stats = service.getCacheStats();

        assertEquals(0, stats.get("totalKeys"));
        assertTrue(stats.containsKey("keysByType"));
        assertTrue(stats.containsKey("localCache"));
    }

    // ==================== getOrCompute / getListOrCompute ====================

    @Test
    void getOrCompute_returnsL1ValueWithoutHittingRedis() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.of("l1"));

        String result = service.getOrCompute("k", String.class, 60, () -> "computed");

        assertEquals("l1", result);
        verifyNoInteractions(valueOps);
    }

    @Test
    void getOrCompute_returnsL2ValueAndPopulatesL1() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn("cached");
        when(objectMapper.convertValue("cached", String.class)).thenReturn("cached");

        String result = service.getOrCompute("k", String.class, 60, () -> "computed");

        assertEquals("cached", result);
    }

    @Test
    void getOrCompute_computesCachesAndReturnsWhenLockAcquired() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        String result = service.getOrCompute("k", String.class, 60, () -> "computed");

        assertEquals("computed", result);
        verify(valueOps).set(eq("bhukkad:k"), eq("computed"), eq(Duration.ofSeconds(60)));
        verify(localCacheService).put("k", "computed");
    }

    @Test
    void getOrCompute_releasesLockWithAcquiredToken() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        service.getOrCompute("k", String.class, 60, () -> "computed");

        // The ownership token used to acquire the lock must be the token passed
        // to the Lua compare-and-delete release, so a stale release is a no-op
        // and can never delete another thread's lock.
        ArgumentCaptor<String> acquireToken = ArgumentCaptor.forClass(String.class);
        verify(stringValueOps).setIfAbsent(eq("bhukkad:cache-lock:k"), acquireToken.capture(), any(Duration.class));
        verify(stringRedisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(List.of("bhukkad:cache-lock:k")),
                eq(acquireToken.getValue()));
    }

    @Test
    void getOrCompute_lockTokenIsUniquePerAcquisition() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        service.getOrCompute("k", String.class, 60, () -> "computed");
        service.getOrCompute("k", String.class, 60, () -> "computed");

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringValueOps, times(2))
                .setIfAbsent(eq("bhukkad:cache-lock:k"), tokenCaptor.capture(), any(Duration.class));
        assertNotEquals(tokenCaptor.getAllValues().get(0), tokenCaptor.getAllValues().get(1));
    }

    @Test
    void getOrCompute_lockAcquiredButSecondReadFindsValue() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null, "from-other-instance");
        when(objectMapper.convertValue("from-other-instance", String.class)).thenReturn("from-other-instance");
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        String result = service.getOrCompute("k", String.class, 60, () -> "computed");

        assertEquals("from-other-instance", result);
    }

    @Test
    void getOrCompute_nullSupplierResult_skipsCacheWrite() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        Supplier<String> nullSupplier = () -> null;
        String result = service.getOrCompute("k", String.class, 60, nullSupplier);

        assertNull(result);
        verify(valueOps, never()).set(anyString(), any(), any(Duration.class));
    }

    @Test
    void getOrCompute_lockNotAcquired_waitsBrieflyThenFallsBackToSupplier() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(false);

        long start = System.currentTimeMillis();
        String result = service.getOrCompute("k", String.class, 60, () -> "fallback");
        long elapsed = System.currentTimeMillis() - start;

        assertEquals("fallback", result);
        // Wait-poll is capped at 3 retries (20+40+60 = 120ms) so a losing thread
        // never blocks for ~1.8s on a cache miss.
        assertTrue(elapsed < 500, "wait-poll should be capped, took " + elapsed + "ms");
    }

    @Test
    void getOrCompute_lockNotAcquired_valueAppearsWhileWaiting() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(objectMapper.convertValue("late-value", String.class)).thenReturn("late-value");
        // First get (initial check) misses; the waitForValue loop's first retry finds it.
        when(valueOps.get("bhukkad:k")).thenReturn(null, "late-value");
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(false);

        String result = service.getOrCompute("k", String.class, 60, () -> "fallback");

        assertEquals("late-value", result);
    }

    @Test
    void getOrCompute_lockAcquisitionThrows_waitsThenComputes() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("lock error"));

        String result = service.getOrCompute("k", String.class, 60, () -> "fallback");

        assertEquals("fallback", result);
    }

    @Test
    void getOrCompute_redisGetThrowsDuringInitialCheck_computes() {
        when(localCacheService.get("k", String.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenThrow(new RuntimeException("redis down"));
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        String result = service.getOrCompute("k", String.class, 60, () -> "computed");

        assertEquals("computed", result);
    }

    @Test
    void getListOrCompute_returnsL1List() {
        List<String> l1List = List.of("a");
        when(localCacheService.get("k", List.class)).thenReturn(Optional.of(l1List));

        List<String> result = service.getListOrCompute("k", String.class, 60, () -> List.of("z"));

        assertSame(l1List, result);
        verifyNoInteractions(valueOps);
    }

    @Test
    void getListOrCompute_returnsL2List() {
        // Use a real ObjectMapper so the cached list is genuinely converted
        RedisCacheService realMapperService = new RedisCacheService(
                redisTemplate, stringRedisTemplate, new ObjectMapper(), localCacheService, distributedInvalidator);
        when(localCacheService.get("k", List.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(List.of("a"));

        List<String> result = realMapperService.getListOrCompute("k", String.class, 60, () -> List.of("z"));

        assertEquals(List.of("a"), result);
    }

    @Test
    void getListOrCompute_lockAcquired_computesAndCaches() {
        when(localCacheService.get("k", List.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        List<String> result = service.getListOrCompute("k", String.class, 60, () -> List.of("x"));

        assertEquals(List.of("x"), result);
        verify(valueOps).set(eq("bhukkad:k"), eq(List.of("x")), eq(Duration.ofSeconds(60)));
        verify(localCacheService).put("k", List.of("x"));
    }

    @Test
    void getListOrCompute_nullSupplierResult_yieldsEmptyList() {
        when(localCacheService.get("k", List.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(true);

        Supplier<List<String>> nullSupplier = () -> null;
        List<String> result = service.getListOrCompute("k", String.class, 60, nullSupplier);

        assertTrue(result.isEmpty());
    }

    @Test
    void getListOrCompute_lockNotAcquired_fallsBackAfterBackoff() {
        when(localCacheService.get("k", List.class)).thenReturn(Optional.empty());
        when(valueOps.get("bhukkad:k")).thenReturn(null);
        when(stringValueOps.setIfAbsent(eq("bhukkad:cache-lock:k"), anyString(), any(Duration.class)))
                .thenReturn(false);

        List<String> result = service.getListOrCompute("k", String.class, 60, () -> List.of("fb"));

        assertEquals(List.of("fb"), result);
    }

    @Test
    void delete_publishesInvalidationEvent() {
        service.delete("menu:42");

        verify(distributedInvalidator).publishInvalidation("menu", "menu:42", false);
    }

    @Test
    void delete_redisError_stillPublishesInvalidation() {
        doThrow(new RuntimeException("down")).when(redisTemplate).delete("bhukkad:menu:42");

        service.delete("menu:42");

        verify(distributedInvalidator).publishInvalidation("menu", "menu:42", false);
    }

    @Test
    void delete_keyAlreadyPrefixed_extractsNameCorrectly() {
        service.delete(CacheConstants.KEY_PREFIX + "menu:42");

        verify(distributedInvalidator).publishInvalidation("menu", CacheConstants.KEY_PREFIX + "menu:42", false);
    }

    @Test
    void deletePattern_publishesPatternInvalidation() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        service.deletePattern("menu:*");

        // extractCacheName stops at the first ':' after prefix stripping
        verify(distributedInvalidator).publishInvalidation("menu", "menu:*", true);
    }

    @Test
    void deletePattern_redisDeleteFails_stillPublishes() {
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn("bhukkad:m:1");
        doThrow(new RuntimeException("del fail")).when(redisTemplate).delete(any(Set.class));

        service.deletePattern("m");

        verify(distributedInvalidator).publishInvalidation("m", "m", true);
    }
}
