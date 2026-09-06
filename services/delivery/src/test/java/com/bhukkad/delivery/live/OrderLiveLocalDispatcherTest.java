package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Node-local fan-out: STOMP whenever a messaging template exists (it is
 * optional), SSE always, and customer/rider destinations only when the
 * update carries the matching ids.
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveLocalDispatcherTest {

    @Mock private OrderSseStreamService sseStreamService;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @Test
    void dispatch_sendsToAllThreeAudiencesWithTemplate() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .orderId(7L).customerId(11L).restaurantId(9L).deliveryAgentId(3L).build();

        new OrderLiveLocalDispatcher(sseStreamService, messagingTemplate).dispatch(update);

        verify(messagingTemplate).convertAndSend(eq("/topic/kitchen/9"), eq(update));
        verify(messagingTemplate).convertAndSend(eq("/topic/order/7"), eq(update));
        verify(messagingTemplate).convertAndSend(eq("/topic/rider/3"), eq(update));
        verify(sseStreamService).broadcastKitchen(9L, update);
        verify(sseStreamService).broadcastCustomer(7L, update);
        verify(sseStreamService).broadcastRider(3L, update);
    }

    @Test
    void dispatch_withoutTemplateStillBroadcastsSseToKitchen() {
        OrderLiveUpdate update = OrderLiveUpdate.builder().restaurantId(9L).build();
        OrderLiveLocalDispatcher dispatcher =
                new OrderLiveLocalDispatcher(sseStreamService, null);

        dispatcher.dispatch(update);

        verify(sseStreamService).broadcastKitchen(9L, update);
        verify(sseStreamService, never()).broadcastCustomer(org.mockito.ArgumentMatchers.any(), eq(update));
        verify(sseStreamService, never()).broadcastRider(org.mockito.ArgumentMatchers.any(), eq(update));
    }

    @Test
    void dispatch_orderWithoutCustomerSkipsCustomerChannel() {
        OrderLiveUpdate update = OrderLiveUpdate.builder().orderId(7L).restaurantId(9L).build();

        new OrderLiveLocalDispatcher(sseStreamService, messagingTemplate).dispatch(update);

        verify(messagingTemplate).convertAndSend(eq("/topic/kitchen/9"), eq(update));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/order/7"), eq(update));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rider/3"), eq(update));
        verify(sseStreamService).broadcastKitchen(9L, update);
        verify(sseStreamService, never()).broadcastCustomer(7L, update);
    }

    @Test
    void dispatch_customerWithoutOrderSkipsCustomerChannel() {
        OrderLiveUpdate update = OrderLiveUpdate.builder().customerId(11L).build();

        new OrderLiveLocalDispatcher(sseStreamService, messagingTemplate).dispatch(update);

        verify(messagingTemplate).convertAndSend(eq("/topic/kitchen/null"), eq(update));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/order/11"), eq(update));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/rider/3"), eq(update));
        verify(sseStreamService).broadcastKitchen(null, update);
        verify(sseStreamService, never()).broadcastCustomer(org.mockito.ArgumentMatchers.any(), eq(update));
        verify(sseStreamService, never()).broadcastRider(org.mockito.ArgumentMatchers.any(), eq(update));
    }
}
