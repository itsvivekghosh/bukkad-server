package com.bhukkad.realtime.serviceImpl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderLiveReplayStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.Topic;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * localConsumers bookkeeping (audit V-07): subscribe registers exactly one Redis
 * listener per topic, and the consumer/listener pair unwinds when streams close
 * so the map cannot grow pod-for-life.
 */
@ExtendWith(MockitoExtension.class)
class RedisOrderLiveRelayTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisMessageListenerContainer listenerContainer;
    @Mock private OrderLiveReplayStore replayStore;

    private RedisOrderLiveRelay relay;

    @BeforeEach
    void setUp() {
        relay = new RedisOrderLiveRelay(stringRedisTemplate, new ObjectMapper(),
                new LiveProperties(), listenerContainer, replayStore);
    }

    @Test
    void subscribe_addsOneListenerPerTopic() {
        relay.subscribe("order:1", update -> { });
        relay.subscribe("order:1", update -> { });

        assertEquals(1, relay.localConsumerTopicCount());
        verify(listenerContainer, times(1)).addMessageListener(any(MessageListener.class), any(Topic.class));
    }

    @Test
    void unsubscribeLastConsumer_removesEntryAndListener() {
        Consumer<OrderLiveUpdate> a = update -> { };
        Consumer<OrderLiveUpdate> b = update -> { };
        relay.subscribe("kitchen:7", a);
        relay.subscribe("kitchen:7", b);

        relay.unsubscribe("kitchen:7", a);
        assertEquals(1, relay.localConsumerTopicCount());
        verify(listenerContainer, never()).removeMessageListener(any(MessageListener.class));

        relay.unsubscribe("kitchen:7", b);
        assertEquals(0, relay.localConsumerTopicCount());
        verify(listenerContainer).removeMessageListener(any(MessageListener.class));
    }

    @Test
    void unsubscribeIsLenientForUnknownTopics() {
        relay.unsubscribe("rider:3", update -> { });
        verify(listenerContainer, never()).addMessageListener(any(MessageListener.class), any(Topic.class));
        verify(listenerContainer, never()).removeMessageListener(any(MessageListener.class));
    }

    @Test
    void unsubscribe_tearsDownTheListenerThatWasRegistered() {
        ArgumentCaptor<MessageListener> added = ArgumentCaptor.forClass(MessageListener.class);
        Consumer<OrderLiveUpdate> a = update -> { };
        relay.subscribe("order:9", a);
        verify(listenerContainer).addMessageListener(added.capture(), any(Topic.class));

        relay.unsubscribe("order:9", a);

        ArgumentCaptor<MessageListener> removed = ArgumentCaptor.forClass(MessageListener.class);
        verify(listenerContainer).removeMessageListener(removed.capture());
        assertEquals(added.getValue(), removed.getValue());
    }
}
