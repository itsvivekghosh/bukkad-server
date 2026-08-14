package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderLiveLocalDispatcherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private OrderSseStreamService sseStreamService;

    @InjectMocks
    private OrderLiveLocalDispatcher dispatcher;

    @Test
    void dispatch_sendsToKitchenAndRiderWhenAgentAssigned() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                .orderId(1L)
                .restaurantId(3L)
                .deliveryAgentId(4L)
                .build();

        dispatcher.dispatch(update);

        verify(messagingTemplate).convertAndSend(eq("/topic/kitchen/3"), eq(update));
        verify(messagingTemplate).convertAndSend(eq("/topic/rider/4"), eq(update));
        verify(sseStreamService).broadcastKitchen(3L, update);
        verify(sseStreamService).broadcastRider(4L, update);
    }

    @Test
    void dispatch_sendsOnlyToKitchenWhenNoAgent() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(1L)
                .restaurantId(3L)
                .build();

        dispatcher.dispatch(update);

        verify(messagingTemplate).convertAndSend(eq("/topic/kitchen/3"), eq(update));
        verify(sseStreamService).broadcastKitchen(3L, update);
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rider/4"), any(OrderLiveUpdate.class));
    }
}
