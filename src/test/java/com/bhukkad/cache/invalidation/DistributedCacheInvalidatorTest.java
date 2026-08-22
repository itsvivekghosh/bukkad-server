package com.bhukkad.cache.invalidation;

import com.bhukkad.cache.event.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;


import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DistributedCacheInvalidatorTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private DistributedCacheInvalidator invalidator;

    @Test
    void publishInvalidation_sendsMessage() throws Exception {
        when(objectMapper.writeValueAsString(any(CacheInvalidatedEvent.class))).thenReturn("{\"cacheName\":\"restaurant\",\"key\":\"1\",\"pattern\":false}");

        invalidator.publishInvalidation("restaurant", "1", false);

        verify(redisTemplate).convertAndSend(eq("bhukkad:cache:invalidation"), eq("{\"cacheName\":\"restaurant\",\"key\":\"1\",\"pattern\":false}"));
    }

    @Test
    void publishInvalidation_swallowsException() throws Exception {
        when(objectMapper.writeValueAsString(any(CacheInvalidatedEvent.class))).thenThrow(new RuntimeException("redis down"));

        // Should not throw
        invalidator.publishInvalidation("restaurant", "1", false);

        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }
}
