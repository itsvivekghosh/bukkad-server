package com.bhukkad.order.domain.service.impl;

import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit batch A: scheduled dispatch must open a real transaction PER ORDER via
 * {@code TransactionTemplate} instead of the old {@code @Transactional}
 * self-invocation, which bypassed the proxy and ran with NO transaction at
 * all. These tests count transaction begin/commit boundaries, verify failure
 * isolation, and assert the transactional method no longer relies on a
 * declarative annotation that cannot be advised on self-invocation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduledOrderProcessorTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderEventPublisher orderEventPublisher;
    @Mock private OrderEtaService orderEtaService;

    private AtomicInteger txBegins;
    private AtomicInteger txRollbacks;
    private ScheduledOrderProcessor processor;

    /** Minimal in-memory tx manager recording begin/commit/rollback per transition. */
    private final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            txBegins.incrementAndGet();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            txRollbacks.incrementAndGet();
        }
    }

    @BeforeEach
    void setUp() {
        txBegins = new AtomicInteger();
        txRollbacks = new AtomicInteger();
        processor = new ScheduledOrderProcessor(orderRepository, orderEventPublisher,
                orderEtaService, new RecordingTransactionManager());
    }

    private Order scheduled(Long id) {
        Order o = new Order();
        o.setId(id);
        o.setStatus(Order.STATUS_SCHEDULED);
        o.setScheduledAt(LocalDateTime.now().minusMinutes(1));
        return o;
    }

    @Test
    void dispatchNextBatch_wrapsEachOrderTransitionInItsOwnTransaction() {
        Order first = scheduled(1L);
        Order second = scheduled(2L);
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.STATUS_SCHEDULED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(first));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(second));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        int processed = processor.dispatchNextBatch();

        assertThat(processed).isEqualTo(2);
        assertThat(txBegins.get()).as("one transaction per order, not per batch").isEqualTo(2);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(o -> {
            assertThat(o.getStatus()).isEqualTo(Order.STATUS_PLACED);
            assertThat(o.getScheduledAt()).isNull();
        });
        verify(orderEventPublisher).orderStatusChanged(1L, Order.STATUS_PLACED);
        verify(orderEventPublisher).orderStatusChanged(2L, Order.STATUS_PLACED);
    }

    @Test
    void perOrderTransaction_rechecksStatusGuard_skipsOrderRacedToCancelled() {
        // The batch read raced with a cancel: the in-transaction re-read shows
        // CANCELLED, so the dispatch must be a no-op (idempotency guard runs
        // inside the fresh transaction, where it can see the committed state).
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.STATUS_SCHEDULED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(scheduled(9L)));
        Order cancelled = scheduled(9L);
        cancelled.setStatus(Order.STATUS_CANCELLED);
        when(orderRepository.findById(9L)).thenReturn(Optional.of(cancelled));

        processor.dispatchNextBatch();

        assertThat(txBegins.get()).isEqualTo(1);
        verify(orderRepository, never()).save(any(Order.class));
        verify(orderEventPublisher, never()).orderStatusChanged(anyLong(), any());
    }

    @Test
    void dispatchNextBatch_oneOrderFailureRollsBackOnlyThatOrderAndContinuesBatch() {
        Order poison = scheduled(1L);
        Order healthy = scheduled(2L);
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.STATUS_SCHEDULED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(poison, healthy));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(poison));
        when(orderRepository.save(poison)).thenThrow(new IllegalStateException("db blip"));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(healthy));
        when(orderRepository.save(healthy)).thenAnswer(inv -> inv.getArgument(0));

        int processed = processor.dispatchNextBatch();

        assertThat(processed).isEqualTo(2);
        assertThat(txBegins.get()).as("both orders attempted in own transactions").isEqualTo(2);
        assertThat(txBegins.get()).isGreaterThan(txRollbacks.get());
        verify(orderEventPublisher).orderStatusChanged(2L, Order.STATUS_PLACED);
    }

    @Test
    void transactionalSelfInvocationTrapCannotReturn_declarativeTxRemoved() {
        // The old bug: dispatchDueOrders() called the @Transactional
        // dispatchNextBatch() on `this`, so the call skipped the proxy — the
        // annotation never took effect. Boundaries are programmatic now.
        for (Method m : ScheduledOrderProcessor.class.getDeclaredMethods()) {
            assertThat(m.isAnnotationPresent(Transactional.class))
                    .as("method %s must not rely on @Transactional (self-invocation bypasses it)",
                            m.getName())
                    .isFalse();
        }
    }

    @Test
    void dispatchDueOrders_drainsUntilBatchComesBackEmpty() {
        Order first = scheduled(1L);
        when(orderRepository.findByStatusAndScheduledAtLessThanEqual(
                eq(Order.STATUS_SCHEDULED), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(first))
                .thenReturn(List.of());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(first));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        processor.dispatchDueOrders();

        verify(orderEventPublisher).orderStatusChanged(1L, Order.STATUS_PLACED);
        assertThat(txBegins.get()).isEqualTo(1);
    }
}
