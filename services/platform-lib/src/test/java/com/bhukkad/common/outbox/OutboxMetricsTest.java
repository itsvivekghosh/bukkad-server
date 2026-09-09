package com.bhukkad.common.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private DeadLetterEventService deadLetterEventService;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void refresh_updatesPendingAndDeadLetterGauges() {
        when(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).thenReturn(7L);
        when(deadLetterEventService.countPending()).thenReturn(3L);
        OutboxMetrics metrics = new OutboxMetrics(outboxEventRepository, deadLetterEventService, registry);

        metrics.refresh();

        assertEquals(7.0, registry.get("bhukkad.outbox.pending").gauge().value());
        assertEquals(3.0, registry.get("bhukkad.outbox.dead_letter").gauge().value());
    }

    @Test
    void refresh_repoFailure_keepsLastKnownPendingValue() {
        when(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING))
                .thenThrow(new RuntimeException("db down"));
        when(deadLetterEventService.countPending()).thenReturn(2L);
        OutboxMetrics metrics = new OutboxMetrics(outboxEventRepository, deadLetterEventService, registry);

        metrics.refresh();

        // Pending gauge stays at its initial 0 on failure; DLQ gauge still updates.
        assertEquals(0.0, registry.get("bhukkad.outbox.pending").gauge().value());
        assertEquals(2.0, registry.get("bhukkad.outbox.dead_letter").gauge().value());
    }

    @Test
    void refresh_dlqFailure_keepsLastKnownDeadLetterValue() {
        when(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).thenReturn(1L);
        when(deadLetterEventService.countPending()).thenThrow(new RuntimeException("redis down"));
        OutboxMetrics metrics = new OutboxMetrics(outboxEventRepository, deadLetterEventService, registry);

        metrics.refresh();

        assertEquals(1.0, registry.get("bhukkad.outbox.pending").gauge().value());
        assertEquals(0.0, registry.get("bhukkad.outbox.dead_letter").gauge().value());
    }
}
