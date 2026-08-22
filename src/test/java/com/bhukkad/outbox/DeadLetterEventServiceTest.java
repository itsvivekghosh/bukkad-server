package com.bhukkad.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterEventServiceTest {

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    void record_copiesOutboxEventToDeadLetter() {
        OutboxEvent event = new OutboxEvent();
        event.setId(7L);
        event.setEventType("ORDER_CREATED");
        event.setAggregateType("ORDER");
        event.setAggregateId(42L);
        event.setPayload("{\"orderId\":42}");
        event.setRetryCount(5);

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        service.record(event, "broker down");

        ArgumentCaptor<DeadLetterEvent> captor = ArgumentCaptor.forClass(DeadLetterEvent.class);
        verify(deadLetterEventRepository).save(captor.capture());
        DeadLetterEvent saved = captor.getValue();
        assertEquals("ORDER_CREATED", saved.getEventType());
        assertEquals("ORDER", saved.getAggregateType());
        assertEquals(42L, saved.getAggregateId());
        assertEquals("{\"orderId\":42}", saved.getPayload());
        assertEquals("broker down", saved.getLastError());
        assertEquals(5, saved.getRetryCount());
        assertEquals(DeadLetterEvent.DlqStatus.PENDING, saved.getStatus());
        assertEquals(DeadLetterEventService.SOURCE_OUTBOX, saved.getSource());
    }

    @Test
    void requeuePending_recreatesOutboxEventAndMarksRequeued() {
        DeadLetterEvent deadLetter = new DeadLetterEvent();
        deadLetter.setId(1L);
        deadLetter.setEventType("ORDER_STATUS_CHANGED");
        deadLetter.setAggregateType("ORDER");
        deadLetter.setAggregateId(42L);
        deadLetter.setPayload("{\"orderId\":42}");

        when(deadLetterEventRepository.findByStatus(eq(DeadLetterEvent.DlqStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(deadLetter));

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        int requeued = service.requeuePending(50);

        assertEquals(1, requeued);
        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent recreated = outboxCaptor.getValue();
        assertEquals("ORDER_STATUS_CHANGED", recreated.getEventType());
        assertEquals(42L, recreated.getAggregateId());
        assertEquals(OutboxEvent.OutboxStatus.PENDING, recreated.getStatus());
        assertEquals(0, recreated.getRetryCount());
        verify(deadLetterEventRepository).markRequeued(
                eq(1L), eq(DeadLetterEvent.DlqStatus.REQUEUED), any());
    }

    @Test
    void record_whenRepositoryThrows_swallowsAndKeepsOriginalOutboxRow() {
        OutboxEvent event = new OutboxEvent();
        event.setId(9L);
        event.setEventType("PAYMENT_CREATED");
        event.setAggregateType("PAYMENT");
        event.setAggregateId(99L);
        event.setPayload("{\"paymentId\":99}");
        event.setRetryCount(3);

        doThrow(new DataAccessException("connection lost") {}).when(deadLetterEventRepository).save(any());

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        service.record(event, "kafka down");

        // The original outbox row must never be touched when the DLQ write fails.
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void requeuePending_whenOutboxSaveFails_skipsAndContinuesOthers() {
        DeadLetterEvent first = new DeadLetterEvent();
        first.setId(1L);
        first.setEventType("ORDER_CREATED");
        first.setAggregateType("ORDER");
        first.setAggregateId(42L);
        first.setPayload("{\"orderId\":42}");

        DeadLetterEvent second = new DeadLetterEvent();
        second.setId(2L);
        second.setEventType("ORDER_STATUS_CHANGED");
        second.setAggregateType("ORDER");
        second.setAggregateId(43L);
        second.setPayload("{\"orderId\":43}");

        when(deadLetterEventRepository.findByStatus(eq(DeadLetterEvent.DlqStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        doThrow(new DataAccessException("insert failed") {}).when(outboxEventRepository).save(any());

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        int requeued = service.requeuePending(50);

        // Both rows failed to requeue; nothing should be marked REQUEUED.
        assertEquals(0, requeued);
        verify(deadLetterEventRepository, never()).markRequeued(any(), any(), any());
    }

    @Test
    void countPending_delegatesToRepository() {
        when(deadLetterEventRepository.countByStatus(DeadLetterEvent.DlqStatus.PENDING)).thenReturn(3L);

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        assertEquals(3L, service.countPending());
    }

    // ==================== ADMIN: DLQ inspection / replay ====================

    private DeadLetterEvent dlqEvent(Long id, DeadLetterEvent.DlqStatus status) {
        DeadLetterEvent e = new DeadLetterEvent();
        e.setId(id);
        e.setEventType("ORDER_CREATED");
        e.setAggregateType("ORDER");
        e.setAggregateId(42L);
        e.setPayload("{\"orderId\":42}");
        e.setLastError("kafka down");
        e.setRetryCount(3);
        e.setStatus(status);
        return e;
    }

    @Test
    void listRecent_delegatesToRepository() {
        when(deadLetterEventRepository.findAllOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(dlqEvent(1L, DeadLetterEvent.DlqStatus.PENDING)));

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        List<DeadLetterEvent> result = service.listRecent(10);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
    }

    @Test
    void getById_returnsEvent() {
        when(deadLetterEventRepository.findById(5L))
                .thenReturn(java.util.Optional.of(dlqEvent(5L, DeadLetterEvent.DlqStatus.PENDING)));

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        assertEquals(5L, service.getById(5L).getId());
    }

    @Test
    void getById_missing_throws() {
        when(deadLetterEventRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        assertThrows(com.bhukkad.exception.ResourceNotFoundException.class,
                () -> service.getById(99L));
    }

    @Test
    void requeueOne_recreatesOutboxAndMarksRequeued() {
        DeadLetterEvent deadLetter = dlqEvent(7L, DeadLetterEvent.DlqStatus.PENDING);
        when(deadLetterEventRepository.findById(7L)).thenReturn(java.util.Optional.of(deadLetter));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        DeadLetterEvent result = service.requeueOne(7L);

        assertEquals(DeadLetterEvent.DlqStatus.REQUEUED, result.getStatus());
        verify(outboxEventRepository).save(any());
        verify(deadLetterEventRepository).markRequeued(
                eq(7L), eq(DeadLetterEvent.DlqStatus.REQUEUED), any());
    }

    @Test
    void requeueOne_alreadyRequeued_isNoOp() {
        DeadLetterEvent deadLetter = dlqEvent(7L, DeadLetterEvent.DlqStatus.REQUEUED);
        when(deadLetterEventRepository.findById(7L)).thenReturn(java.util.Optional.of(deadLetter));

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        DeadLetterEvent result = service.requeueOne(7L);

        assertEquals(DeadLetterEvent.DlqStatus.REQUEUED, result.getStatus());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void requeueOne_missing_throws() {
        when(deadLetterEventRepository.findById(8L)).thenReturn(java.util.Optional.empty());

        DeadLetterEventService service = new DeadLetterEventService(deadLetterEventRepository, outboxEventRepository);
        assertThrows(com.bhukkad.exception.ResourceNotFoundException.class,
                () -> service.requeueOne(8L));
    }
}
