package com.bhukkad.social.feed;

import com.bhukkad.social.event.PostCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeedInvalidationHandlerTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;

    @Mock
    private CaffeineCache l1Cache;

    @InjectMocks
    private FeedInvalidationHandler handler;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        when(redisTemplateProvider.getIfAvailable()).thenReturn(redisTemplate);
    }

    @Test
    void onPostCreated_publishesInvalidationForAffectedGeohashCells() {
        var event = new PostCreatedEvent(1L, 28.6139, 77.2090, 10L);

        handler.onPostCreated(event);

        // Verify Redis pub/sub was called for each affected cell (center + neighbors)
        verify(redisTemplate, atLeast(9)).convertAndSend(eq("feed:invalidation"), any(String.class));
    }

    @Test
    void onPostCreated_correctlyEncodesGeohash() {
        var event = new PostCreatedEvent(1L, 28.6139, 77.2090, 10L);

        handler.onPostCreated(event);

        verify(redisTemplate).convertAndSend(eq("feed:invalidation"), eq("ttn9"));
    }

    @Test
    void handleInvalidation_deletesMatchingRedisKeys() throws Exception {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            RedisCallback<List<String>> callback = invocation.getArgument(0);
            return callback.doInRedis(org.mockito.Mockito.mock(org.springframework.data.redis.connection.RedisConnection.class));
        });

        handler.handleInvalidation("tdr");

        verify(redisTemplate).execute(any(RedisCallback.class));
    }

    @Test
    void handleInvalidation_whenNoRedisKeys_doesNothing() throws Exception {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            return List.of();
        });

        handler.handleInvalidation("tdr");

        verify(redisTemplate, never()).unlink(any(String.class));
        verify(redisTemplate, never()).unlink(any(java.util.Collection.class));
    }

    @Test
    void handleInvalidation_whenRedisKeysIsNull_doesNothing() throws Exception {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            return null;
        });

        handler.handleInvalidation("tdr");

        verify(redisTemplate, never()).unlink(any(String.class));
        verify(redisTemplate, never()).unlink(any(java.util.Collection.class));
    }

    @Test
    void handleInvalidation_alwaysInvalidatesL1Cache() throws Exception {
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            return List.of();
        });

        handler.handleInvalidation("tdr");

        verify(l1Cache).invalidate();
    }

    @Test
    void onMessage_ignoresUnknownChannels() {
        var message = mock(Message.class);
        when(message.getChannel()).thenReturn("unknown:channel".getBytes());

        handler.onMessage(message, "pattern".getBytes());

        verify(redisTemplate, never()).execute(any(RedisCallback.class));
        verify(l1Cache, never()).invalidate();
    }

    @Test
    void onMessage_processesKnownChannel() throws Exception {
        var message = mock(Message.class);
        when(message.getChannel()).thenReturn("feed:invalidation".getBytes());
        when(message.getBody()).thenReturn("tdr".getBytes());
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            return List.of();
        });

        handler.onMessage(message, "pattern".getBytes());

        verify(l1Cache).invalidate();
    }

    @Test
    void onMessage_handlesEmptyBodyGracefully() throws Exception {
        var message = mock(Message.class);
        when(message.getChannel()).thenReturn("feed:invalidation".getBytes());
        when(message.getBody()).thenReturn(new byte[0]);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
            return List.of();
        });

        handler.onMessage(message, "pattern".getBytes());

        verify(l1Cache).invalidate();
    }
}
