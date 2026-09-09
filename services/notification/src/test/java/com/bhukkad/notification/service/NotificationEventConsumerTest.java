package com.bhukkad.notification.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NotificationEventConsumer} under the PERF-2 contract
 * (V-10 no-swallow, V-22 poison customerId, P-07 bounded hand-off with
 * claim-before-dispatch and reject-then-DLT).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private NotificationDispatchService dispatchService;
    @Mock
    private IdempotencyRecordRepository idempotencyRecords;

    private SimpleMeterRegistry meterRegistry;
    private NotificationEventConsumer consumer;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Inline hand-off: dispatch() executes on the caller thread so the
        // unit test is deterministic (the production pool is covered by
        // NotificationDispatchConfigTest for sizing/policy).
        executor = inlineExecutor();
        consumer = newConsumer(executor);
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), anyLong(),
                anyString(), any(), any(LocalDateTime.class))).thenReturn(1);
    }

    private NotificationEventConsumer newConsumer(ThreadPoolTaskExecutor exec) {
        return new NotificationEventConsumer(dispatchService, objectMapper, idempotencyRecords,
                exec, new TransactionTemplate(new NoOpTxManager()), meterRegistry);
    }

    private static ThreadPoolTaskExecutor inlineExecutor() {
        var exec = mock(ThreadPoolTaskExecutor.class);
        org.mockito.Mockito.doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(exec).execute(any(Runnable.class));
        return exec;
    }

    @Test
    void orderCreatedEvent_claimsThenDispatches() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()),
                eq(IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name()),
                eq(7L),
                eq(IdempotencyRecord.IdempotencyStatus.COMPLETED.name()),
                any(), any(LocalDateTime.class));
        verify(dispatchService).dispatch(eq("email"), eq("customer-7"),
                eq("order-confirmation"), anyString(), anyString());
        assertThat(meterRegistry.find("notification_dispatch_duration")
                .tag("channel", "email").timer()).isNotNull();
    }

    @Test
    void nonOrderEvent_isDeliberatelySkipped() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42",
                "{\"orderId\":42,\"status\":\"DELIVERED\"}");

        assertThatCode(() -> consumer.onOrderEvent(objectMapper.writeValueAsString(event)))
                .doesNotThrowAnyException();
        verifyNoInteractions(dispatchService);
        verifyNoInteractions(idempotencyRecords);
    }

    /** V-10 fail-first rewrite: the old test asserted the swallow. Poison now THROWS. */
    @Test
    void malformedPayload_throwsForDltRouting() {
        assertThatThrownBy(() -> consumer.onOrderEvent("not-json"))
                .isInstanceOf(PoisonEventException.class);
        verifyNoInteractions(dispatchService);
    }

    /** V-22: extracted customer id 0/missing is poison — never a customer-0 send. */
    @Test
    void missingCustomerId_throwsPoison_andNeverDispatches() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"orderId\":42,\"restaurantId\":10}");

        assertThatThrownBy(() -> consumer.onOrderEvent(objectMapper.writeValueAsString(event)))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("customerId");

        verifyNoInteractions(dispatchService);
        verifyNoInteractions(idempotencyRecords); // claim is NOT burned for poison
    }

    @Test
    void duplicateEventId_releasedWithoutDispatch() throws Exception {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), anyLong(),
                anyString(), any(), any(LocalDateTime.class))).thenReturn(0);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"customerId\":7}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verifyNoInteractions(dispatchService);
    }

    /**
     * P-07: bounded queue full -> AbortPolicy rejection rethrows (so the
     * DefaultErrorHandler parks the record on the DLT) AND the dedupe claim is
     * released, keeping the later DLT replay dispatchable.
     */
    @Test
    void executorRejection_releasesClaimAndRethrowsToDlt() throws Exception {
        var rejecting = mock(ThreadPoolTaskExecutor.class);
        org.mockito.Mockito.doThrow(new RejectedExecutionException("queue full"))
                .when(rejecting).execute(any(Runnable.class));
        var rejectingConsumer = newConsumer(rejecting);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"customerId\":7}");

        assertThatThrownBy(() -> rejectingConsumer.onOrderEvent(objectMapper.writeValueAsString(event)))
                .isInstanceOf(RejectedExecutionException.class);

        verify(idempotencyRecords).deleteByScopeAndIdempotencyKey(
                IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME, event.eventId());
        verifyNoInteractions(dispatchService);
    }

    @SuppressWarnings("unused")
    private static final class NoOpTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction,
                               org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
