package com.bhukkad.notification;

import com.bhukkad.entity.Order;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.event.kafka.PlatformEventMessage;
import com.bhukkad.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link NotificationDispatchService} — the bridge that maps
 * platform event messages to {@link NotificationService} calls.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private NotificationDispatchService dispatchService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Construct explicitly: @InjectMocks cannot supply the ObjectMapper constructor arg.
        dispatchService = new NotificationDispatchService(notificationService, objectMapper);
    }

    private PlatformEventMessage message(String type, String payload) {
        return new PlatformEventMessage(type, 42L, payload, Instant.now());
    }

    @Test
    void dispatch_orderCreated_sendsConfirmation() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new OrderCreatedEvent(42L, "ORD-1", 7L, 10L, LocalDateTime.now()));
        dispatchService.dispatch(message("ORDER_CREATED", payload));

        verify(notificationService).sendOrderConfirmation(42L);
    }

    @Test
    void dispatch_orderStatusChanged_sendsStatusUpdate() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new OrderStatusChangedEvent(42L, "ORD-1", 7L, 10L, 99L,
                        Order.OrderStatus.PLACED, Order.OrderStatus.DELIVERED, LocalDateTime.now()));
        dispatchService.dispatch(message("ORDER_STATUS_CHANGED", payload));

        verify(notificationService).sendOrderStatusUpdate(42L, "DELIVERED");
    }

    @Test
    void dispatch_orderAgentAssigned_sendsDeliveryAssignment() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new OrderAgentAssignedEvent(42L, "ORD-1", 7L, 10L, 99L,
                        Order.OrderStatus.READY_FOR_PICKUP, LocalDateTime.now()));
        dispatchService.dispatch(message("ORDER_AGENT_ASSIGNED", payload));

        verify(notificationService).sendDeliveryAssignment(42L, 99L);
    }

    @Test
    void dispatch_unknownEventType_skipsSilently() {
        dispatchService.dispatch(message("UNKNOWN_EVENT", "{}"));

        verifyNoInteractions(notificationService);
    }

    @Test
    void dispatch_nullMessage_noop() {
        dispatchService.dispatch(null);
        verifyNoInteractions(notificationService);
    }

    @Test
    void dispatch_nullEventType_noop() {
        PlatformEventMessage nullType = new PlatformEventMessage(null, 42L, "{}", Instant.now());
        dispatchService.dispatch(nullType);
        verifyNoInteractions(notificationService);
    }

    @Test
    void dispatch_malformedPayload_doesNotThrow() {
        dispatchService.dispatch(message("ORDER_CREATED", "not-json"));

        verifyNoInteractions(notificationService);
    }
}
