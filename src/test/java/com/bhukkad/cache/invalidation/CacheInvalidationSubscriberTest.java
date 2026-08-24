package com.bhukkad.cache.invalidation;

import com.bhukkad.cache.event.CacheInvalidatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationSubscriberTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CacheInvalidationSubscriber subscriber;

    /**
     * The publisher serializes the event to JSON and sends it via
     * RedisTemplate.convertAndSend; GenericJackson2JsonRedisSerializer wraps the
     * String payload in JSON quotes. The subscriber therefore deserializes the
     * body as a String first, then parses the event from that string.
     */
    private void stubTwoStepDeserialize(CacheInvalidatedEvent event) throws Exception {
        String innerJson = "{\"cacheName\":\"restaurant\",\"key\":\"restaurant:1\",\"pattern\":false}";
        when(objectMapper.readValue(any(byte[].class), eq(String.class))).thenReturn(innerJson);
        when(objectMapper.readValue(innerJson, CacheInvalidatedEvent.class)).thenReturn(event);
    }

    @Test
    void onMessage_deletesExactKey() throws Exception {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant:1", false);
        stubTwoStepDeserialize(event);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("\"{\\\"cacheName\\\":\\\"restaurant\\\"}\"".getBytes());

        subscriber.onMessage(message, new byte[0]);

        verify(redisTemplate).delete("bhukkad:restaurant:1");
    }

    @Test
    void onMessage_deletesPatternKeys() throws Exception {
        CacheInvalidatedEvent event = new CacheInvalidatedEvent("restaurant", "restaurant", true);
        when(objectMapper.readValue(any(byte[].class), eq(String.class)))
                .thenReturn("{\"cacheName\":\"restaurant\",\"key\":\"restaurant\",\"pattern\":true}");
        when(objectMapper.readValue(anyString(), eq(CacheInvalidatedEvent.class))).thenReturn(event);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("\"{}\"".getBytes());

        subscriber.onMessage(message, new byte[0]);

        verify(redisTemplate).keys("bhukkad:restaurant*");
    }

    @Test
    void onMessage_swallowsException() throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(String.class)))
                .thenThrow(new RuntimeException("bad json"));

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("garbage".getBytes());

        // Must not throw — cache invalidation is best-effort.
        subscriber.onMessage(message, new byte[0]);
    }
}
