package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Redis pub/sub relay ingress: decode, hand to the local dispatcher on the
 * SSE executor, and never let a bad frame crash the listener container.
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveRedisSubscriberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private OrderLiveLocalDispatcher localDispatcher;

    @Test
    void onMessage_deserializesAndDispatchesOnExecutor() throws Exception {
        byte[] body = "{\"orderId\":42,\"eventType\":\"RIDER_LOCATION\"}".getBytes(StandardCharsets.UTF_8);
        Message message = mock(Message.class);
        whenBody(message, body);
        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, Runnable::run);

        subscriber.onMessage(message, null);

        ArgumentCaptor<OrderLiveUpdate> captor = ArgumentCaptor.forClass(OrderLiveUpdate.class);
        verify(localDispatcher).dispatch(captor.capture());
        assertThat(captor.getValue().getOrderId()).isEqualTo(42L);
        assertThat(captor.getValue().getEventType()).isEqualTo(OrderLiveUpdate.EventType.RIDER_LOCATION);
    }

    @Test
    void onMessage_nullMessageIgnored() {
        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, Runnable::run);

        assertThatCode(() -> subscriber.onMessage(null, null)).doesNotThrowAnyException();
        verifyNoInteractions(localDispatcher);
    }

    @Test
    void onMessage_nullBodyIgnored() {
        Message message = mock(Message.class);
        whenBody(message, null);
        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, Runnable::run);

        subscriber.onMessage(message, null);

        verify(localDispatcher, never()).dispatch(any());
    }

    @Test
    void onMessage_emptyBodyIgnored() {
        Message message = mock(Message.class);
        whenBody(message, new byte[0]);
        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, Runnable::run);

        subscriber.onMessage(message, null);

        verify(localDispatcher, never()).dispatch(any());
    }

    @Test
    void onMessage_malformedPayloadSwallowed() {
        Message message = mock(Message.class);
        whenBody(message, "this is not json".getBytes(StandardCharsets.UTF_8));
        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, Runnable::run);

        assertThatCode(() -> subscriber.onMessage(message, null)).doesNotThrowAnyException();
        verifyNoInteractions(localDispatcher);
    }

    private static void whenBody(Message message, byte[] body) {
        org.mockito.Mockito.lenient().when(message.getBody()).thenReturn(body);
    }
}
