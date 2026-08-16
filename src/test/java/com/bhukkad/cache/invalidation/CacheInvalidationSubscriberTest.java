package com.bhukkad.cache.invalidation;

import com.bhukkad.cache.event.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationSubscriberTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CacheInvalidationSubscriber subscriber;

    @Test
    void onMessage_deletesExactKey() throws Exception {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant:1", false);
        String json = "{\"cacheName\":\"restaurant\",\"key\":\"restaurant:1\",\"pattern\":false}";
        when(objectMapper.readValue(any(byte[].class), eq(CacheInvalidatedEvent.class))).thenReturn(event);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes());

        subscriber.onMessage(message, new byte[0]);

        verify(redisTemplate).delete("bhukkad:restaurant:1");
    }

    @Test
    void onMessage_deletesPatternKeys() throws Exception {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant", true);
        String json = "{\"cacheName\":\"restaurant\",\"key\":\"restaurant\",\"pattern\":true}";
        when(objectMapper.readValue(any(byte[].class), eq(CacheInvalidatedEvent.class))).thenReturn(event);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(json.getBytes());

        subscriber.onMessage(message, new byte[0]);

        verify(redisTemplate).keys("bhukkad:restaurant*");
    }

    @Test
    void onMessage_swallowsException() throws Exception {
        when(objectMapper.readValue(anyString(), any(Class.class))).thenThrow(new RuntimeException("bad json"));

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("bad".getBytes());

        // Should not throw
        subscriber.onMessage(message, new byte[0]);

        verify(redisTemplate, never()).delete(anyString());
    }
}
