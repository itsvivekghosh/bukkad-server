package com.bhukkad.common.cache;

import com.bhukkad.common.cache.LocalCacheService;
import com.bhukkad.common.cache.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationSubscriberTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private LocalCacheService localCacheService;
    @Mock
    private Cursor<String> cursor;

    private CacheInvalidationSubscriber subscriber;

    private void stubTwoStepDeserialize(CacheInvalidatedEvent event) throws Exception {
        String innerJson = "{\"cacheName\":\"restaurant\",\"key\":\"restaurant:1\",\"pattern\":false}";
        when(objectMapper.readValue(any(byte[].class), eq(String.class))).thenReturn(innerJson);
        when(objectMapper.readValue(innerJson, CacheInvalidatedEvent.class)).thenReturn(event);
    }

    @Test
    void onMessage_key_deletesL2AndInvalidatesL1() throws Exception {
        subscriber = new CacheInvalidationSubscriber(redisTemplate, objectMapper, localCacheService);
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant:1", false);
        stubTwoStepDeserialize(event);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("\"{\\\"cacheName\\\":\\\"restaurant\\\"}\"".getBytes());

        subscriber.onMessage(message, new byte[0]);

        // L2 (Redis) exact key deleted
        verify(redisTemplate).delete("bhukkad:restaurant:1");
        // L1 (Caffeine) exact key invalidated
        verify(localCacheService).invalidate("restaurant:1");
    }

    @Test
    void onMessage_pattern_scansThenDeletesAndClearsL1() throws Exception {
        subscriber = new CacheInvalidationSubscriber(redisTemplate, objectMapper, localCacheService);
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant", true);
        when(objectMapper.readValue(any(byte[].class), eq(String.class)))
                .thenReturn("{\"cacheName\":\"restaurant\",\"key\":\"restaurant\",\"pattern\":true}");
        when(objectMapper.readValue(anyString(), eq(CacheInvalidatedEvent.class))).thenReturn(event);
        // SCAN returns two matching keys
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("bhukkad:restaurant:1", "bhukkad:restaurant:2");

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("\"{}\"".getBytes());

        subscriber.onMessage(message, new byte[0]);

        // L2 pattern delete via SCAN (not KEYS)
        verify(redisTemplate, never()).keys(anyString());
        verify(redisTemplate).delete(Set.of("bhukkad:restaurant:1", "bhukkad:restaurant:2"));
        // L1 fully cleared (Caffeine has no prefix eviction)
        verify(localCacheService).clearAll();
    }

    @Test
    void onMessage_swallowsException() throws Exception {
        subscriber = new CacheInvalidationSubscriber(redisTemplate, objectMapper, localCacheService);
        when(objectMapper.readValue(any(byte[].class), eq(String.class)))
                .thenThrow(new RuntimeException("bad json"));

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("garbage".getBytes());

        // Must not throw — cache invalidation is best-effort.
        assertDoesNotThrow(() -> subscriber.onMessage(message, new byte[0]));
    }
}