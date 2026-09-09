package com.bhukkad.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventService service;

    @BeforeEach
    void setUp() {
        service = new OutboxEventService(outboxEventRepository, new ObjectMapper());
        // G-1 guard (PERF-2/D6): enqueue requires an ambient business tx.
        // Unit tests emulate the proxy having opened one (the real @Transactional
        // MANDATORY enforcement is exercised through Spring, not here).
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test void enqueue_withoutTransaction_throwsG1Violation() {
        TransactionSynchronizationManager.setActualTransactionActive(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.enqueue("ORDER_CREATED", 9L, "{}"));
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("G-1 violation");
        verify(outboxEventRepository, org.mockito.Mockito.never()).save(any(OutboxEvent.class));

        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @Test void enqueue_savesPendingEventWithSerializedPayload() {
        service.enqueue("ORDER_CREATED", 42L, new java.util.HashMap<>(java.util.Map.of("id", 42)));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();
        assertEquals("ORDER_CREATED", saved.getEventType());
        assertEquals("ORDER", saved.getAggregateType());
        assertEquals(42L, saved.getAggregateId());
        assertEquals(OutboxEvent.OutboxStatus.PENDING, saved.getStatus());
        assertEquals("{\"id\":42}", saved.getPayload());
    }

    @Test void enqueue_withNullPayload_serializesNull() {
        service.enqueue("ORDER_STATUS_CHANGED", 1L, null);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertEquals("null", captor.getValue().getPayload());
    }

    @Test void enqueue_whenRepositoryFails_throwsIllegalState() {
        doThrow(new RuntimeException("db down")).when(outboxEventRepository).save(any(OutboxEvent.class));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.enqueue("ORDER_CREATED", 9L, "{}"));
        assertEquals("Failed to enqueue outbox event: ORDER_CREATED", ex.getMessage());
    }
}
