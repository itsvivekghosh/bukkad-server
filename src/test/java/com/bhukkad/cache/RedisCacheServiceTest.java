package com.bhukkad.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.bhukkad.cache.invalidation.DistributedCacheInvalidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private LocalCacheService localCacheService;
    @Mock
    private DistributedCacheInvalidator invalidator;

    private RedisCacheService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(localCacheService.isEnabled()).thenReturn(false);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new RedisCacheService(redisTemplate, objectMapper, localCacheService, invalidator);
    }

    @Test
    void set_successWithoutPrefix() {
        service.set("restaurant:1", "value", 60);

        verify(valueOperations).set("bhukkad:restaurant:1", "value", Duration.ofSeconds(60));
    }

    @Test
    void set_successWithPrefix() {
        service.set("bhukkad:restaurant:1", "value", 30);

        verify(valueOperations).set("bhukkad:restaurant:1", "value", Duration.ofSeconds(30));
    }

    @Test
    void set_exceptionIsSwallowed() {
        doThrow(new RuntimeException("redis down")).when(valueOperations)
                .set(anyString(), any(), any(Duration.class));

        assertDoesNotThrow(() -> service.set("k", "v", 10));
    }

    @Test
    void get_hit() {
        when(valueOperations.get("bhukkad:k")).thenReturn("hello");

        Optional<String> result = service.get("k", String.class);

        assertTrue(result.isPresent());
        assertEquals("hello", result.get());
    }

    @Test
    void get_miss() {
        when(valueOperations.get("bhukkad:k")).thenReturn(null);

        assertTrue(service.get("k", String.class).isEmpty());
    }

    @Test
    void get_exceptionReturnsEmpty() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("fail"));

        assertTrue(service.get("k", String.class).isEmpty());
    }

    @Test
    void get_convertFailureReturnsEmpty() {
        when(valueOperations.get("bhukkad:k")).thenReturn("hello");

        assertTrue(service.get("k", Integer.class).isEmpty());
    }

    @Test
    void getList_hit() {
        when(valueOperations.get("bhukkad:k")).thenReturn(List.of("a", "b"));

        Optional<List<String>> result = service.getList("k", String.class);

        assertTrue(result.isPresent());
        assertEquals(List.of("a", "b"), result.get());
    }

    @Test
    void getList_miss() {
        when(valueOperations.get("bhukkad:k")).thenReturn(null);

        assertTrue(service.getList("k", String.class).isEmpty());
    }

    @Test
    void getList_exceptionReturnsEmpty() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("fail"));

        assertTrue(service.getList("k", String.class).isEmpty());
    }

    @Test
    void delete_successAndException() {
        service.delete("k");
        verify(redisTemplate).delete("bhukkad:k");

        doThrow(new RuntimeException("fail")).when(redisTemplate).delete(anyString());
        assertDoesNotThrow(() -> service.delete("k"));
    }

    @Test
    void deletePattern_nullKeys() {
        when(redisTemplate.keys("bhukkad:rest*")).thenReturn(null);

        service.deletePattern("rest");

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void deletePattern_emptyKeys() {
        when(redisTemplate.keys("bhukkad:rest*")).thenReturn(Collections.emptySet());

        service.deletePattern("rest");

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void deletePattern_keysPresent() {
        Set<String> keys = Set.of("bhukkad:restaurant:1");
        when(redisTemplate.keys("bhukkad:rest*")).thenReturn(keys);

        service.deletePattern("rest");

        verify(redisTemplate).delete(keys);
    }

    @Test
    void deletePattern_exceptionIsSwallowed() {
        when(redisTemplate.keys(anyString())).thenThrow(new RuntimeException("fail"));

        assertDoesNotThrow(() -> service.deletePattern("rest"));
    }

    @Test
    void exists_trueFalseAndException() {
        when(redisTemplate.hasKey("bhukkad:k")).thenReturn(true);
        assertTrue(service.exists("k"));

        when(redisTemplate.hasKey("bhukkad:k")).thenReturn(false);
        assertFalse(service.exists("k"));

        when(redisTemplate.hasKey("bhukkad:k")).thenReturn(null);
        assertFalse(service.exists("k"));

        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("fail"));
        assertFalse(service.exists("k"));
    }

    @Test
    void setExpiry_successAndException() {
        service.setExpiry("k", 15);
        verify(redisTemplate).expire("bhukkad:k", 15, TimeUnit.SECONDS);

        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new RuntimeException("fail"));
        assertDoesNotThrow(() -> service.setExpiry("k", 15));
    }

    @Test
    void hSet_successAndException() {
        service.hSet("hash", "f", "v");
        verify(hashOperations).put("bhukkad:hash", "f", "v");

        doThrow(new RuntimeException("fail")).when(hashOperations).put(anyString(), any(), any());
        assertDoesNotThrow(() -> service.hSet("hash", "f", "v"));
    }

    @Test
    void hGet_hitMissExceptionAndConvertFailure() {
        when(hashOperations.get("bhukkad:hash", "f")).thenReturn("val");
        assertEquals(Optional.of("val"), service.hGet("hash", "f", String.class));

        when(hashOperations.get("bhukkad:hash", "f")).thenReturn(null);
        assertTrue(service.hGet("hash", "f", String.class).isEmpty());

        when(hashOperations.get(anyString(), any())).thenThrow(new RuntimeException("fail"));
        assertTrue(service.hGet("hash", "f", String.class).isEmpty());
    }

    @Test
    void hGet_convertFailureReturnsEmpty() {
        when(hashOperations.get("bhukkad:hash", "f")).thenReturn("val");

        assertTrue(service.hGet("hash", "f", Integer.class).isEmpty());
    }

    @Test
    void hDelete_successAndException() {
        service.hDelete("hash", "a", "b");
        verify(hashOperations).delete("bhukkad:hash", "a", "b");

        doThrow(new RuntimeException("fail")).when(hashOperations).delete(anyString(), any(), any());
        assertDoesNotThrow(() -> service.hDelete("hash", "a", "b"));
    }

    @Test
    void increment_successAndException() {
        when(valueOperations.increment("bhukkad:c")).thenReturn(5L);
        assertEquals(5L, service.increment("c"));

        when(valueOperations.increment(anyString())).thenThrow(new RuntimeException("fail"));
        assertNull(service.increment("c"));
    }

    @Test
    void clearAll_nullKeys() {
        when(redisTemplate.keys("bhukkad:*")).thenReturn(null);

        service.clearAll();

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void clearAll_emptyKeys() {
        when(redisTemplate.keys("bhukkad:*")).thenReturn(Collections.emptySet());

        service.clearAll();

        verify(redisTemplate, never()).delete(anyCollection());
    }

    @Test
    void clearAll_keysPresent() {
        Set<String> keys = Set.of("bhukkad:a");
        when(redisTemplate.keys("bhukkad:*")).thenReturn(keys);

        service.clearAll();

        verify(redisTemplate).delete(keys);
    }

    @Test
    void clearAll_exceptionIsSwallowed() {
        when(redisTemplate.keys("bhukkad:*")).thenThrow(new RuntimeException("fail"));

        assertDoesNotThrow(() -> service.clearAll());
    }

    @Test
    void getCacheStats_keysWithPrefixAndOther() {
        when(redisTemplate.keys("bhukkad:*")).thenReturn(Set.of(
                "bhukkad:restaurant:1",
                "bhukkad:order:2",
                "nocolon"
        ));

        Map<String, Object> stats = service.getCacheStats();

        assertEquals(3, stats.get("totalKeys"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> byType = (Map<String, Integer>) stats.get("keysByType");
        assertEquals(1, byType.get("restaurant"));
        assertEquals(1, byType.get("order"));
        assertEquals(1, byType.get("other"));
    }

    @Test
    void getOrCompute_cacheHitSkipsSupplier() {
        when(valueOperations.get("bhukkad:hot-key")).thenReturn("cached");

        String result = service.getOrCompute("hot-key", String.class, 60, () -> "loaded");

        assertEquals("cached", result);
        verify(valueOperations, never()).set(eq("bhukkad:hot-key"), eq("loaded"), any(Duration.class));
    }

    @Test
    void getCacheStats_nullKeys() {
        when(redisTemplate.keys("bhukkad:*")).thenReturn(null);

        Map<String, Object> stats = service.getCacheStats();

        assertEquals(0, stats.get("totalKeys"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> byType = (Map<String, Integer>) stats.get("keysByType");
        assertTrue(byType.isEmpty());
    }

    @Test
    void getCacheStats_exception() {
        when(redisTemplate.keys("bhukkad:*")).thenThrow(new RuntimeException("fail"));

        Map<String, Object> stats = service.getCacheStats();

        assertEquals("fail", stats.get("error"));
    }
}
