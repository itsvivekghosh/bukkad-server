package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderLiveRedisSubscriberTest {

    @Mock
    private OrderLiveLocalDispatcher localDispatcher;

    private OrderLiveRedisSubscriber subscriber;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        subscriber = new OrderLiveRedisSubscriber(objectMapper, localDispatcher);
    }

    @Test
    void onMessage_dispatchesParsedUpdate() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                .orderId(9L)
                .restaurantId(3L)
                .changedAt(LocalDateTime.parse("2026-08-14T10:00:00"))
                .build();
        byte[] body = objectMapper.writeValueAsBytes(update);

        subscriber.onMessage(new DefaultMessage("bhukkad:live:order-updates".getBytes(StandardCharsets.UTF_8), body), null);

        verify(localDispatcher).dispatch(any(OrderLiveUpdate.class));
    }

    @Test
    void onMessage_ignoresEmptyPayload() {
        subscriber.onMessage(new DefaultMessage("channel".getBytes(StandardCharsets.UTF_8), new byte[0]), null);

        verifyNoInteractions(localDispatcher);
    }
}
