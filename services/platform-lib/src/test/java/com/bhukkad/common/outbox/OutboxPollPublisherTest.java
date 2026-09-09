package com.bhukkad.common.outbox;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the two-phase {@link OutboxPollPublisher} (PERF-2/B1).
 *
 * <p>The transaction boundary is emulated with a real
 * {@link TransactionTemplate} over a no-op
 * {@link AbstractPlatformTransactionManager}, so
 * {@link TransactionSynchronizationManager} activation state is real: the
 * tests assert the claim/state updates run INSIDE a transaction and the Kafka
 * publish runs OUTSIDE any transaction — the property the old self-invoked
 * {@code @Transactional} silently lacked.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxPollPublisherTest {

    @Mock
    private OutboxEventRepository repository;
    @Mock
    private KafkaPlatformEventPublisher publisher;
    @Mock
    private DeadLetterEventService deadLetterEvents;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPollPublisher relay;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        relay = newRelay(OutboxProperties.defaults());
    }

    @AfterEach
    void tearDown() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive())
                .as("relay must not leak tx synchronisation").isFalse();
    }

    private OutboxPollPublisher newRelay(OutboxProperties properties) {
        return new OutboxPollPublisher(repository, publisher, properties,
                new TransactionTemplate(new NoOpTxManager()), deadLetterEvents, meterRegistry);
    }

    @Test
    void drainBatch_emptyClaim_publishesNothing() {
        when(repository.findPendingForProcessing(eq("PENDING"), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(relay.drainBatch()).isZero();
        verify(publisher, never()).publishForResult(any());
        verify(repository, never()).markPublished(anyList(), any());
    }

    @Test
    void drainBatch_publishesAcksWithOneBatchedUpdate() {
        OutboxEvent e1 = event(1L, PlatformEventMessage.of("OrderCreated", "1", "{\"id\":1}"));
        OutboxEvent e2 = event(2L, PlatformEventMessage.of("OrderCreated", "2", "{\"id\":2}"));
        stubClaim(e1, e2);
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenReturn(true);

        int published = relay.drainBatch();

        assertThat(published).isEqualTo(2);
        // Phase 1: ONE batched claim flip ...
        verify(repository).markProcessing(eq(List.of(1L, 2L)), any(LocalDateTime.class));
        // Phase 3: ... and ONE batched ack flip; never per-row saves (PERF-3.5).
        verify(repository).markPublished(eq(List.of(1L, 2L)), any(LocalDateTime.class));
        verify(repository, never()).save(any(OutboxEvent.class));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void drainBatch_claimRunsInTxPublishRunsOutsideAnyTx() {
        OutboxEvent e = event(1L, PlatformEventMessage.of("OrderCreated", "1", "{\"id\":1}"));
        stubClaim(e);

        AtomicBoolean publishSawTx = new AtomicBoolean(true);
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenAnswer(inv -> {
            // The broker send must NOT sit inside the claim/state transaction
            // (B1: connections must not be held across network round-trips).
            publishSawTx.set(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        AtomicBoolean claimSawTx = new AtomicBoolean(false);
        when(repository.findPendingForProcessing(eq("PENDING"), anyInt(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    claimSawTx.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return List.of(e);
                });

        relay.drainBatch();

        assertThat(claimSawTx).as("claim runs inside a transaction").isTrue();
        assertThat(publishSawTx).as("publish runs with NO transaction active").isFalse();
    }

    @Test
    void drainBatch_publishFailureQueuesRetryWithBackoff() {
        OutboxEvent e = event(1L, PlatformEventMessage.of("OrderCreated", "1", "{\"id\":1}"));
        stubClaim(e);
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenReturn(false);

        int published = relay.drainBatch();

        assertThat(published).isZero();
        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).markPendingRetry(eq(List.of(1L)), next.capture(), anyString());
        assertThat(next.getValue()).isAfter(LocalDateTime.now()); // exponential backoff
        verify(repository, never()).markPublished(anyList(), any());
        verify(deadLetterEvents, never()).record(any(), anyString());
    }

    @Test
    void drainBatch_exhaustedRetries_deadLettersViaServiceAndCounter() {
        OutboxEvent e = event(1L, PlatformEventMessage.of("OrderCreated", "1", "{\"id\":1}"));
        e.setRetryCount(OutboxProperties.defaults().maxRetries() - 1); // this attempt hits the cap
        stubClaim(e);
        when(publisher.publishForResult(any(PlatformEventMessage.class))).thenReturn(false);

        relay.drainBatch();

        verify(deadLetterEvents).record(eq(e), anyString());
        verify(repository).markFailed(eq(List.of(1L)), anyString());
        verify(repository, never()).markPendingRetry(anyList(), any(), any());
        assertThat(meterRegistry.get("outbox.dlq").counter().count()).isEqualTo(1.0);
    }

    @Test
    void drainBatch_malformedPayload_goesStraightToDeadLetter() {
        OutboxEvent e = event(1L, null);
        e.setPayload("not-json{{{");
        stubClaim(e);

        relay.drainBatch();

        verify(publisher, never()).publishForResult(any());
        verify(deadLetterEvents).record(eq(e), anyString());
        verify(repository).markFailed(eq(List.of(1L)), anyString());
        assertThat(meterRegistry.get("outbox.dlq").counter().count()).isEqualTo(1.0);
    }

    @Test
    void recoverStale_batchesIntoOneUpdateAndRunsInTx() {
        when(repository.recoverStaleToPending(any(LocalDateTime.class), anyString()))
                .thenAnswer(inv -> {
                    assertThatTxActive();
                    return 3;
                });

        int recovered = relay.recoverStale();

        assertThat(recovered).isEqualTo(3);
        verify(repository).recoverStaleToPending(any(LocalDateTime.class), anyString());
        verify(repository, never()).save(any(OutboxEvent.class));
    }

    @Test
    void claim_skipsRowsDeferredByBackoff_repositoryFilter() {
        // The relay defers the next_attempt_at filter to the claim query; a row
        // claiming at a future next_attempt_at is simply not selected.
        when(repository.findPendingForProcessing(eq("PENDING"), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of());

        assertThat(relay.drainBatch()).isZero();
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findPendingForProcessing(eq("PENDING"), eq(100), now.capture());
        assertThat(now.getValue()).isBetween(LocalDateTime.now().minusSeconds(30), LocalDateTime.now());
    }

    @Test
    void properties_maxRetriesAndBackoffDefaults() {
        OutboxProperties p = OutboxProperties.defaults();
        assertThat(p.maxRetries()).isEqualTo(5);
        assertThat(p.backoffFor(1)).isEqualTo(p.retryBackoff());
        assertThat(p.backoffFor(2).toMillis())
                .isEqualTo(p.retryBackoff().multipliedBy(2).toMillis());
        // Unset (0/null) values normalise to defaults so a service without any
        // outbox yml block still relays (PERF-2 wake-the-wire fix).
        OutboxProperties unset = new OutboxProperties(0, null, null, null, 0, 0, null);
        assertThat(unset.batchSize()).isEqualTo(100);
        assertThat(unset.pollInterval().toSeconds()).isEqualTo(5);
        assertThat(unset.maxRetries()).isEqualTo(5);
        assertThat(unset.retryBackoff().toSeconds()).isEqualTo(2);
    }

    private void stubClaim(OutboxEvent... events) {
        when(repository.findPendingForProcessing(eq("PENDING"), anyInt(), any(LocalDateTime.class)))
                .thenReturn(List.of(events))
                .thenReturn(List.of());
    }

    private static void assertThatTxActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new AssertionError("expected an active transaction");
        }
    }

    /** Minimal transaction manager that only toggles synchronisation state. */
    private static final class NoOpTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    private static OutboxEvent event(Long id, PlatformEventMessage message) {
        OutboxEvent e = new OutboxEvent();
        e.setId(id);
        e.setEventType("OrderCreated");
        e.setAggregateType("ORDER");
        e.setAggregateId(id);
        e.setPayload(message == null ? "{}" : message.toJson());
        e.setStatus(OutboxEvent.OutboxStatus.PENDING);
        return e;
    }
}
