package com.bhukkad.outbox;

import com.bhukkad.config.OutboxProperties;
import com.bhukkad.event.ExternalEventBridge;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.PaymentWebhookReceivedEvent;
import com.bhukkad.logging.alert.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ExternalEventBridge externalEventBridge;

    @Mock
    private DeadLetterEventService deadLetterEventService;

    @Mock
    private AlertService alertService;

    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventProcessor outboxEventProcessor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        OutboxProperties outboxProperties = new OutboxProperties();
        outboxProperties.setMaxRetries(5);
        outboxProperties.setBatchSize(50);
        outboxProperties.setDeadLetterBatchSize(50);
        outboxEventProcessor = new OutboxEventProcessor(
                outboxEventRepository, eventPublisher, objectMapper,
                externalEventBridge, deadLetterEventService, outboxProperties, alertService);
    }

    private OutboxEvent pendingEvent(long id, String eventType, String payload) {
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(id);
        outboxEvent.setEventType(eventType);
        outboxEvent.setPayload(payload);
        outboxEvent.setStatus(OutboxEvent.OutboxStatus.PENDING);
        outboxEvent.setRetryCount(0);
        return outboxEvent;
    }

    @Test
    void processPendingEvents_claimsPendingBatchViaSkipLockedQuery() {
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of());

        outboxEventProcessor.processPendingEvents();

        verify(outboxEventRepository).findPendingForProcessing("PENDING", 50);
    }

    @Test
    void processPendingEvents_publishesAndMarksPublished() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(
                1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(
                10L, "ORDER_CREATED", objectMapper.writeValueAsString(createdEvent));

        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(1L, captor.getValue().orderId());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    void processPendingEvents_deadLettersEventAfterMaxRetries() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(
                1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(
                10L, "ORDER_CREATED", objectMapper.writeValueAsString(createdEvent));
        outboxEvent.setRetryCount(4); // one more attempt reaches max (5)

        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        org.mockito.Mockito.doThrow(new RuntimeException("broker down"))
                .when(externalEventBridge).forward(outboxEvent);

        outboxEventProcessor.processPendingEvents();

        assertEquals(OutboxEvent.OutboxStatus.FAILED, outboxEvent.getStatus());
        assertEquals(5, outboxEvent.getRetryCount());
        verify(deadLetterEventService).record(outboxEvent, "broker down");
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    void processPendingEvents_unknownEventType_deadLettersAfterRetries() throws Exception {
        OutboxEvent outboxEvent = pendingEvent(11L, "UNKNOWN_TYPE", "{}");
        outboxEvent.setRetryCount(4); // one more attempt reaches max (5)

        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        assertEquals(OutboxEvent.OutboxStatus.FAILED, outboxEvent.getStatus());
        assertEquals(5, outboxEvent.getRetryCount());
        verify(deadLetterEventService).record(eq(outboxEvent), any(String.class));
    }

    @Test
    void processPendingEvents_paymentWebhookReceived_isRoutedNotDeadLettered() throws Exception {
        Map<String, Object> payload = Map.of(
                "eventId", "evt_123",
                "gatewayOrderId", "order_Oxyz",
                "gatewayPaymentId", "pay_Lxyz");
        OutboxEvent outboxEvent = pendingEvent(
                12L, "PAYMENT_WEBHOOK_RECEIVED", objectMapper.writeValueAsString(payload));

        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<PaymentWebhookReceivedEvent> captor =
                ArgumentCaptor.forClass(PaymentWebhookReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("evt_123", captor.getValue().eventId());
        assertEquals("order_Oxyz", captor.getValue().gatewayOrderId());
        assertEquals("pay_Lxyz", captor.getValue().gatewayPaymentId());

        // The webhook event must NOT dead-letter: it is marked published.
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
        verify(deadLetterEventService, never()).record(any(), any());
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    void requeueDeadLetters_delegatesToDeadLetterService() {
        outboxEventProcessor.requeueDeadLetters();
        verify(deadLetterEventService).requeuePending(50);
    }
}
