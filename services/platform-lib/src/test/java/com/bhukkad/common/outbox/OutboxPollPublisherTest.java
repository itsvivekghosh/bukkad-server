package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutboxPollPublisher}. No Docker required: the claim / publish /
 * state-transition logic is exercised against mocked dependencies, giving a
 * deterministic, always-green regression guard for the outbox relay.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPollPublisherTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaPlatformEventPublisher publisher;

    private OutboxPollPublisher relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxPollPublisher(repository, publisher, OutboxProperties.defaults());
    }

    @Test
    void drainBatch_emptyClaim_publishesNothing() {
        when(repository.findPendingForProcessing(eq(OutboxEvent.OutboxStatus.PENDING.name()), anyInt()))
                .thenReturn(List.of());

        int published = relay.drainBatch();

        assertThat(published).isZero();
        verify(repository, never()).saveAll(any());
        verify(publisher, never()).publishForResult(any());
    }

    @Test
    void drainBatch_claimsFlipsProcessingAndPublishesPendingRows() {
        OutboxEvent e1 = event(1L, OutboxEvent.OutboxStatus.PENDING,
                PlatformEventMessage.of("OrderCreated", "1", "{\"id\":1}").toJson());
        OutboxEvent e2 = event(2L, OutboxEvent.OutboxStatus.PENDING,
                PlatformEventMessage.of("OrderCreated", "2", "{\"id\":2}").toJson());
        when(repository.findPendingForProcessing(eq(OutboxEvent.OutboxStatus.PENDING.name()), anyInt()))
                .thenReturn(List.of(e1, e2));
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenReturn(true);

        int published = relay.drainBatch();

        assertThat(published).isEqualTo(2);
        // Both claimed and flipped to PROCESSING, then saved (claim) — then PUBLISHED.
        InOrder order = inOrder(repository);
        order.verify(repository).saveAll(List.of(e1, e2));
        order.verify(repository, times(2)).save(any(OutboxEvent.class));
        assertThat(e1.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(e2.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        assertThat(e1.getPublishedAt()).isNotNull();
        assertThat(e2.getPublishedAt()).isNotNull();
        assertThat(e1.getLastError()).isNull();
    }

    @Test
    void drainBatch_publishFailure_leavesRequeueableAndBumpsRetry() {
        OutboxEvent e = event(1L, OutboxEvent.OutboxStatus.PENDING,
                PlatformEventMessage.of("OrderCreated", "1", "{\"status\":\"PLACED\"}").toJson());
        when(repository.findPendingForProcessing(eq(OutboxEvent.OutboxStatus.PENDING.name()), anyInt()))
                .thenReturn(List.of(e));
        when(publisher.publishForResult(any(PlatformEventMessage.class)))
                .thenReturn(false); // broker timeout

        int published = relay.drainBatch();

        assertThat(published).isZero();
        // Kept PROCESSING so recoverStale() can re-queue it; retry bumped + error recorded.
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PROCESSING);
        assertThat(e.getRetryCount()).isEqualTo(1);
        assertThat(e.getLastError()).isNotNull();
        verify(repository, never()).countByStatus(any());
    }

    @Test
    void drainBatch_malformedPayload_movesToFailedDeadLetter() {
        OutboxEvent e = event(1L, OutboxEvent.OutboxStatus.PENDING, "not-json{{{");
        when(repository.findPendingForProcessing(eq(OutboxEvent.OutboxStatus.PENDING.name()), anyInt()))
                .thenReturn(List.of(e));

        int published = relay.drainBatch();

        assertThat(published).isZero();
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.FAILED);
        assertThat(e.getRetryCount()).isEqualTo(1);
        assertThat(e.getLastError()).isNotNull();
    }

    @Test
    void recoverStale_requeuesAbandonedProcessingRows() {
        OutboxEvent stale = event(1L, OutboxEvent.OutboxStatus.PROCESSING, "evt-1");
        stale.setProcessingStartedAt(java.time.LocalDateTime.now().minusSeconds(120));
        when(repository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of(stale));

        int recovered = relay.recoverStale();

        assertThat(recovered).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PENDING);
        assertThat(stale.getProcessingStartedAt()).isNull();
        verify(repository).save(stale);
    }

    @Test
    void recoverStale_noStale_noop() {
        when(repository.findStaleProcessing(eq(OutboxEvent.OutboxStatus.PROCESSING), any(java.time.LocalDateTime.class)))
                .thenReturn(List.of());

        int recovered = relay.recoverStale();

        assertThat(recovered).isZero();
        verify(repository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void pendingCount_sumsPendingAndProcessing() {
        when(repository.countByStatus(OutboxEvent.OutboxStatus.PENDING)).thenReturn(3L);
        when(repository.countByStatus(OutboxEvent.OutboxStatus.PROCESSING)).thenReturn(2L);

        assertThat(relay.pendingCount()).isEqualTo(5);
    }

    @Test
    void drainBatch_disabledProperties_isNoop() {
        OutboxPollPublisher disabled = new OutboxPollPublisher(
                repository, publisher, new OutboxProperties(0, Duration.ZERO, Duration.ZERO, Duration.ZERO, 0));

        assertThat(disabled.drainBatch()).isZero();
        verify(repository, never()).findPendingForProcessing(anyString(), anyInt());
    }

    @Test
    void claimBatch_flipvPendingToProcessingBeforePublish() {
        OutboxEvent e = event(1L, OutboxEvent.OutboxStatus.PENDING,
                PlatformEventMessage.of("OrderCreated", "1", "{\"payload\":\"x\"}").toJson());
        when(repository.findPendingForProcessing(eq(OutboxEvent.OutboxStatus.PENDING.name()), anyInt()))
                .thenReturn(List.of(e));
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenReturn(true);

        relay.drainBatch();

        // The claim (status->PROCESSING, processingStartedAt set) must precede the publish.
        InOrder order = inOrder(repository, publisher);
        order.verify(repository).findPendingForProcessing(anyString(), anyInt());
        order.verify(repository).saveAll(List.of(e));
        order.verify(publisher).publishForResult(any(PlatformEventMessage.class));
        assertThat(e.getStatus()).isEqualTo(OutboxEvent.OutboxStatus.PUBLISHED);
        ArgumentCaptor<PlatformEventMessage> msg = ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(publisher).publishForResult(msg.capture());
        assertThat(msg.getValue().eventType()).isEqualTo("OrderCreated");
    }

    private static OutboxEvent event(Long id, OutboxEvent.OutboxStatus status, String rawPayload) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setEventType("OrderCreated");
        e.setAggregateType("ORDER");
        e.setAggregateId(1L);
        // rawPayload is stored verbatim as the outbox payload (a serialized envelope).
        e.setPayload(rawPayload);
        e.setStatus(status);
        return e;
    }
}
