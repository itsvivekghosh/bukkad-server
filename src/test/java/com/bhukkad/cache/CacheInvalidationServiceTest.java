package com.bhukkad.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationServiceTest {

    private static final String CHANNEL = "bhukkad:cache:invalidate";

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RedisConnectionFactory connectionFactory;
    @Mock
    private LocalCacheService localCacheService;

    private CacheInvalidationService service() {
        return new CacheInvalidationService(stringRedisTemplate, connectionFactory, localCacheService, CHANNEL);
    }

    @Test
    void publishInvalidation_sendsKeyOnChannel() {
        service().publishInvalidation("restaurant:42");

        verify(stringRedisTemplate).convertAndSend(CHANNEL, "restaurant:42");
    }

    @Test
    void publishInvalidation_redisFailure_isSwallowed() {
        doThrow(new RuntimeException("redis down"))
                .when(stringRedisTemplate).convertAndSend(anyString(), any());

        assertDoesNotThrow(() -> service().publishInvalidation("restaurant:42"));
    }

    @Test
    void publishInvalidation_nullKey_isSwallowed() {
        assertDoesNotThrow(() -> service().publishInvalidation(null));
    }

    @Test
    void onMessage_evictsLocalCacheForKey() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("restaurant:42".getBytes(StandardCharsets.UTF_8));

        service().onMessage(message, null);

        verify(localCacheService).invalidate("restaurant:42");
    }

    @Test
    void onMessage_malformedMessage_isSwallowed() {
        Message message = mock(Message.class);
        when(message.getBody()).thenThrow(new RuntimeException("bad body"));

        assertDoesNotThrow(() -> service().onMessage(message, null));
    }

    @Test
    void onMessage_localEvictionFailure_isSwallowed() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("k".getBytes(StandardCharsets.UTF_8));
        doThrow(new RuntimeException("evict failed")).when(localCacheService).invalidate("k");

        assertDoesNotThrow(() -> service().onMessage(message, null));
    }

    @Test
    void listenerContainer_isConfiguredButNotStarted() {
        RedisMessageListenerContainer container = service().localCacheInvalidationListenerContainer();

        assertNotNull(container);
        assertFalse(container.isRunning());
    }
}