package com.bhukkad.common.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P-06: the Redis wake publisher targets {@code bhukkad:outbox:wake:<service>}
 * and NEVER propagates a Redis failure into the business transaction — the
 * periodic poll is the correctness backstop.
 */
@ExtendWith(MockitoExtension.class)
class RedisOutboxWakePublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void publish_sendsToServiceWakeChannel() {
        RedisOutboxWakePublisher publisher = new RedisOutboxWakePublisher(redisTemplate, "order-service");

        publisher.publishAfterCommit("OrderCreated");

        verify(redisTemplate).convertAndSend(eq("bhukkad:outbox:wake:order-service"), eq("OrderCreated"));
    }

    @Test
    void publish_redisFailure_swallowed() {
        when(redisTemplate.convertAndSend(anyString(), anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        RedisOutboxWakePublisher publisher = new RedisOutboxWakePublisher(redisTemplate, "order-service");

        assertThatCode(() -> publisher.publishAfterCommit("OrderCreated"))
                .as("a lost wake must never break the enqueuing transaction")
                .doesNotThrowAnyException();
    }

    @Test
    void noopPublisher_doesNothing() {
        OutboxWakePublisher noop = OutboxWakePublisher.noop();

        assertThatCode(() -> noop.publishAfterCommit("OrderCreated")).doesNotThrowAnyException();
        verifyNoInteractions(redisTemplate);
    }
}
