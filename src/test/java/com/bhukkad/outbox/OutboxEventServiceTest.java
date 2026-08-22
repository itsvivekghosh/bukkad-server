package com.bhukkad.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
