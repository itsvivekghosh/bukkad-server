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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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
    @Mock
    private PlatformTransactionManager transactionManager;

    private ObjectMapper objectMapper;
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
                externalEventBridge, deadLetterEventService, outboxProperties, alertService, transactionManager);
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
    void processPendingEvents_claimsPendingBatchAndPublishesAndMarksPublished() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(10L, "ORDER_CREATED",
                objectMapper.writeValueAsString(createdEvent));
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(10L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(1L, captor.getValue().orderId());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
        assertNotNull(outboxEvent.getPublishedAt());
        verify(outboxEventRepository).save(outboxEvent);
    }

    @Test
    void processPendingEvents_claimMarksProcessing() {
        OutboxEvent outboxEvent = pendingEvent(10L, "ORDER_CREATED", "{}");
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<OutboxEvent> saved = inv.getArgument(0);
            assertEquals(OutboxEvent.OutboxStatus.PROCESSING, saved.get(0).getStatus());
            assertNotNull(saved.get(0).getProcessingStartedAt());
            return saved;
        });

        outboxEventProcessor.processPendingEvents();
    }

    @Test
    void processPendingEvents_noPendingEvents_doesNothing() {
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of());
        outboxEventProcessor.processPendingEvents();
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void processPendingEvents_deadLettersAfterMaxRetries() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(10L, "ORDER_CREATED",
                objectMapper.writeValueAsString(createdEvent));
        outboxEvent.setRetryCount(4); // one more attempt reaches max (5)
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(10L)).thenReturn(Optional.of(outboxEvent));
        doThrow(new RuntimeException("broker down")).when(externalEventBridge).forward(outboxEvent);

        outboxEventProcessor.processPendingEvents();

        assertEquals(OutboxEvent.OutboxStatus.FAILED, outboxEvent.getStatus());
        assertEquals(5, outboxEvent.getRetryCount());
        verify(deadLetterEventService).record(any(OutboxEvent.class), eq("broker down"));
    }

    @Test
    void processPendingEvents_forwardFailure_belowMaxRetries_resetsToPending() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(10L, "ORDER_CREATED",
                objectMapper.writeValueAsString(createdEvent));
        outboxEvent.setRetryCount(1); // still below max (5)
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(10L)).thenReturn(Optional.of(outboxEvent));
        doThrow(new RuntimeException("transient")).when(externalEventBridge).forward(outboxEvent);

        outboxEventProcessor.processPendingEvents();

        assertEquals(OutboxEvent.OutboxStatus.PENDING, outboxEvent.getStatus()); // back to queue
        assertEquals(2, outboxEvent.getRetryCount());
        verify(deadLetterEventService, never()).record(any(), any());
    }

    @Test
    void processPendingEvents_unknownEventType_deadLettersAfterRetries() throws Exception {
        OutboxEvent outboxEvent = pendingEvent(11L, "UNKNOWN_TYPE", "{}");
        outboxEvent.setRetryCount(4);
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(11L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        assertEquals(OutboxEvent.OutboxStatus.FAILED, outboxEvent.getStatus());
        assertEquals(5, outboxEvent.getRetryCount());
        verify(deadLetterEventService).record(eq(outboxEvent), any(String.class));
    }

    @Test
    void processPendingEvents_paymentWebhookReceived_isRoutedNotDeadLettered() throws Exception {
        Map<String, Object> payload = Map.of("eventId", "evt_123", "gatewayOrderId", "order_Oxyz", "gatewayPaymentId", "pay_Lxyz");
        OutboxEvent outboxEvent = pendingEvent(12L, "PAYMENT_WEBHOOK_RECEIVED",
                objectMapper.writeValueAsString(payload));
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(12L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<PaymentWebhookReceivedEvent> captor = ArgumentCaptor.forClass(PaymentWebhookReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals("evt_123", captor.getValue().eventId());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
        verify(deadLetterEventService, never()).record(any(), any());
    }

    @Test
    void recoverStaleProcessing_resetsToPending() {
        OutboxEvent staleEvent = pendingEvent(20L, "ORDER_CREATED", "{}");
        staleEvent.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        staleEvent.setProcessingStartedAt(LocalDateTime.now().minusMinutes(15));
        when(outboxEventRepository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of(staleEvent));
        when(outboxEventRepository.findById(20L)).thenReturn(Optional.of(staleEvent));

        outboxEventProcessor.recoverStaleProcessing();

        assertEquals(OutboxEvent.OutboxStatus.PENDING, staleEvent.getStatus());
        assertEquals("processing abandoned (recovered from stale PROCESSING)", staleEvent.getLastError());
        verify(outboxEventRepository).save(staleEvent);
    }

    @Test
    void recoverStaleProcessing_noStaleEvents_doesNothing() {
        when(outboxEventRepository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of());
        outboxEventProcessor.recoverStaleProcessing();
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void requeueDeadLetters_delegatesToDeadLetterService() {
        outboxEventProcessor.requeueDeadLetters();
        verify(deadLetterEventService).requeuePending(50);
    }

    // ===== Batch E: remaining publish-type + finalize/recovery branches =====

    @Test
    void publish_orderStatusChanged_publishesTypedEvent() throws Exception {
        com.bhukkad.event.OrderStatusChangedEvent statusEvent =
                new com.bhukkad.event.OrderStatusChangedEvent(1L, "ORD-1", 2L, 3L, null,
                        com.bhukkad.entity.Order.OrderStatus.PLACED,
                        com.bhukkad.entity.Order.OrderStatus.CONFIRMED, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(30L, "ORDER_STATUS_CHANGED",
                objectMapper.writeValueAsString(statusEvent));
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(30L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(com.bhukkad.event.OrderStatusChangedEvent.class, captor.getValue().getClass());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
    }

    @Test
    void publish_orderAgentAssigned_publishesTypedEvent() throws Exception {
        com.bhukkad.event.OrderAgentAssignedEvent assignedEvent =
                new com.bhukkad.event.OrderAgentAssignedEvent(1L, "ORD-1", 2L, 3L, 9L,
                        com.bhukkad.entity.Order.OrderStatus.CONFIRMED, LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(31L, "ORDER_AGENT_ASSIGNED",
                objectMapper.writeValueAsString(assignedEvent));
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(31L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(com.bhukkad.event.OrderAgentAssignedEvent.class, captor.getValue().getClass());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
    }

    @Test
    void publish_orderItemsSnapshot_publishesTypedEvent() throws Exception {
        com.bhukkad.event.OrderItemsSnapshotEvent snapshotEvent =
                new com.bhukkad.event.OrderItemsSnapshotEvent(1L, "ORD-1", 3L,
                        java.util.List.of(new com.bhukkad.event.OrderItemsSnapshotEvent.Item(
                                100L, "Butter Chicken", 2)), LocalDateTime.now());
        OutboxEvent outboxEvent = pendingEvent(32L, "ORDER_ITEMS_SNAPSHOT",
                objectMapper.writeValueAsString(snapshotEvent));
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.findById(32L)).thenReturn(Optional.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(com.bhukkad.event.OrderItemsSnapshotEvent.class, captor.getValue().getClass());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
    }

    @Test
    void finalizeFailure_eventDeletedBetweenClaimAndFinalize_skipsDeadLetter() throws Exception {
        OutboxEvent outboxEvent = pendingEvent(33L, "UNKNOWN_TYPE", "{}");
        when(outboxEventRepository.findPendingForProcessing(eq("PENDING"), eq(50)))
                .thenReturn(List.of(outboxEvent));
        when(outboxEventRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        // Row vanished (purged concurrently) before finalizeFailure reads it
        when(outboxEventRepository.findById(33L)).thenReturn(Optional.empty());

        outboxEventProcessor.processPendingEvents();

        verify(deadLetterEventService, never()).record(any(), any());
        verify(alertService, never()).alertException(any(), any(), any());
    }

    @Test
    void recoverStaleProcessing_rowNoLongerProcessing_skipsReset() {
        OutboxEvent staleEvent = pendingEvent(34L, "ORDER_CREATED", "{}");
        staleEvent.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        when(outboxEventRepository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of(staleEvent));
        // Between discovery and reset the row moved on (already re-published)
        OutboxEvent current = pendingEvent(34L, "ORDER_CREATED", "{}");
        current.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
        when(outboxEventRepository.findById(34L)).thenReturn(Optional.of(current));

        outboxEventProcessor.recoverStaleProcessing();

        // The PUBLISHED row must not be overwritten back to PENDING
        verify(outboxEventRepository, never()).save(any());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, current.getStatus());
    }

    @Test
    void recoverStaleProcessing_saveFailure_doesNotAbortSweep() {
        OutboxEvent staleEvent = pendingEvent(35L, "ORDER_CREATED", "{}");
        staleEvent.setStatus(OutboxEvent.OutboxStatus.PROCESSING);
        when(outboxEventRepository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(LocalDateTime.class)))
                .thenReturn(List.of(staleEvent));
        when(outboxEventRepository.findById(35L)).thenReturn(Optional.of(staleEvent));
        when(outboxEventRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> outboxEventProcessor.recoverStaleProcessing());
    }

    @Test
    void requeueDeadLetters_interrupted_returnsWithoutRequeue() {
        // Pre-set the interrupt flag: the jitter Thread.sleep returns immediately
        // and the sweep must NOT hit the dead-letter service.
        Thread.currentThread().interrupt();

        outboxEventProcessor.requeueDeadLetters();

        Thread.interrupted(); // clear flag for subsequent tests
        verify(deadLetterEventService, never()).requeuePending(anyInt());
    }
}