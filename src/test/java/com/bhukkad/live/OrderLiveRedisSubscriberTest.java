package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLiveRedisSubscriberTest {

    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private OrderLiveLocalDispatcher localDispatcher;
    @Mock
    private Executor sseDispatchExecutor;

    @Test
    void onMessage_offloadsDispatchToExecutor() throws Exception {
        OrderLiveUpdate update = new OrderLiveUpdate();
        update.setOrderId(1L);
        when(objectMapper.readValue(any(byte[].class), eq(OrderLiveUpdate.class))).thenReturn(update);

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(new byte[]{1, 2, 3});

        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, sseDispatchExecutor);
        subscriber.onMessage(message, new byte[0]);

        // The Redis listener thread must NOT run the fan-out inline; it is
        // submitted to the bounded dispatch pool.
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(sseDispatchExecutor).execute(captor.capture());
        verify(localDispatcher, never()).dispatch(any());
        captor.getValue().run();
        verify(localDispatcher).dispatch(update);
    }

    @Test
    void onMessage_nullBody_doesNotDispatch() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(null);

        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, sseDispatchExecutor);
        subscriber.onMessage(message, new byte[0]);

        verify(sseDispatchExecutor, never()).execute(any());
    }

    @Test
    void onMessage_deserializationFailure_isSwallowed() throws Exception {
        when(objectMapper.readValue(any(byte[].class), eq(OrderLiveUpdate.class)))
                .thenThrow(new RuntimeException("bad payload"));

        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(new byte[]{1, 2, 3});

        OrderLiveRedisSubscriber subscriber =
                new OrderLiveRedisSubscriber(objectMapper, localDispatcher, sseDispatchExecutor);
        assertDoesNotThrow(() -> subscriber.onMessage(message, new byte[0]));
        verify(sseDispatchExecutor, never()).execute(any());
    }
}
