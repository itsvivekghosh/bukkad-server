package com.bhukkad.realtime.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderLiveEventConsumerTest {

    @Mock
    private OrderLiveRelay relay;

    private OrderLiveEventConsumer consumer;

    @BeforeEach
    void initConsumer() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new OrderLiveEventConsumer(relay, mapper);
    }

    @Test
    void consumesOrderStatusChanged_relaysLiveUpdateEvent() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42",
                "{\"orderId\":42,\"status\":\"DELIVERED\"}");

        consumer.onOrderEvent(event.toJson());

        ArgumentCaptor<LiveUpdateEvent> captor = ArgumentCaptor.forClass(LiveUpdateEvent.class);
        verify(relay).relay(captor.capture());

        LiveUpdateEvent live = captor.getValue();
        assertEquals("OrderStatusChanged", live.getType());
        OrderLiveUpdate update = (OrderLiveUpdate) live.getPayload();
        assertEquals(OrderLiveUpdate.EventType.STATUS_CHANGED, update.getEventType());
        assertEquals(42L, update.getOrderId());
        assertEquals("DELIVERED", update.getStatus());
        assertNull(update.getEventId());
    }

    @Test
    void consumesOrderCreated_relaysKitchenAndCustomerFields() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10}");

        consumer.onOrderEvent(event.toJson());

        ArgumentCaptor<LiveUpdateEvent> captor = ArgumentCaptor.forClass(LiveUpdateEvent.class);
        verify(relay).relay(captor.capture());

        LiveUpdateEvent live = captor.getValue();
        assertEquals("OrderCreated", live.getType());
        OrderLiveUpdate update = (OrderLiveUpdate) live.getPayload();
        assertEquals(OrderLiveUpdate.EventType.ORDER_CREATED, update.getEventType());
        assertEquals(42L, update.getOrderId());
        assertEquals(7L, update.getCustomerId());
        assertEquals(10L, update.getRestaurantId());
        assertEquals("PLACED", update.getStatus());
    }

    @Test
    void ignoredEventType_isNotRelayed() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCancelled", "42", "{\"orderId\":42}");

        consumer.onOrderEvent(event.toJson());

        verifyNoInteractions(relay);
    }

    @Test
    void malformedPayload_isSwallowedWithoutRelay() {
        consumer.onOrderEvent("{not valid json}");

        verifyNoInteractions(relay);
    }
}
