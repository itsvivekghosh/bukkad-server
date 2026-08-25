package com.bhukkad.payment;

import com.bhukkad.entity.Payment;
import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import com.bhukkad.testutil.InMemoryHashOperations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DunningService}, the failed-payment retry scheduler.
 *
 * <p>{@code retryDelayMs} is set to 0 in each test so the queued retry is due
 * immediately and a single scheduler pass can be exercised deterministically.
 * Redis state is simulated with an in-memory hash store so retry scheduling on
 * one "replica" is observable by another.</p>
 */
@ExtendWith(MockitoExtension.class)
class DunningServiceTest {

    private static final Long PAYMENT_ID = 1L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private AlertService alertService;

    private InMemoryHashOperations<String, String, String> hashStore;

    private DunningService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hashStore = new InMemoryHashOperations<>();
        service = new DunningService(paymentRepository, paymentService, alertService, replicaRedisTemplate());
        service.setEnabled(true);
        service.setMaxRetries(3);
        service.setRetryDelayMs(0);
        service.setAlertAfterFailure(true);
    }

    private Payment paymentWithStatus(Payment.PaymentStatus status) {
        Payment payment = new Payment();
        payment.setId(PAYMENT_ID);
        payment.setStatus(status);
        return payment;
    }

    @Test
    void scheduleRetry_queuesPaymentForRetry() {
        service.scheduleRetry(PAYMENT_ID);

        assertTrue(service.isRetryScheduled(PAYMENT_ID));
        assertEquals(0, service.getRetryCount(PAYMENT_ID));
    }

    @Test
    void scheduleRetry_nullPaymentId_noop() {
        service.scheduleRetry(null);

        assertFalse(service.isRetryScheduled(PAYMENT_ID));
    }

    @Test
    void retryPendingPayments_success_removesFromQueueWithoutRetryCount() {
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();

        verify(paymentService).processPayment(eq(PAYMENT_ID), eq("dunning-" + PAYMENT_ID));
        assertFalse(service.isRetryScheduled(PAYMENT_ID));
        assertEquals(0, service.getRetryCount(PAYMENT_ID));
    }

    @Test
    void retryPendingPayments_failure_incrementsRetryCountAndReschedules() {
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));
        org.mockito.Mockito.doThrow(new IllegalStateException("gateway timeout"))
                .when(paymentService).processPayment(eq(PAYMENT_ID), anyString());
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();

        verify(paymentService).processPayment(eq(PAYMENT_ID), eq("dunning-" + PAYMENT_ID));
        assertEquals(1, service.getRetryCount(PAYMENT_ID));
        assertTrue(service.isRetryScheduled(PAYMENT_ID));
        verify(alertService, never()).alertException(anyString(), anyString(), any());
    }

    @Test
    void retryPendingPayments_notYetDue_skipsRetry() {
        service.setRetryDelayMs(60_000);
        lenient().when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();

        verify(paymentService, never()).processPayment(eq(PAYMENT_ID), anyString());
        assertTrue(service.isRetryScheduled(PAYMENT_ID));
    }

    @Test
    void retryPendingPayments_terminalStatus_dropsFromQueue() {
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.COMPLETED)));
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();

        verify(paymentService, never()).processPayment(eq(PAYMENT_ID), anyString());
        assertFalse(service.isRetryScheduled(PAYMENT_ID));
    }

    @Test
    void retryPendingPayments_maxRetriesExhausted_alertsAndStops() {
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));
        org.mockito.Mockito.doThrow(new IllegalStateException("gateway down"))
                .when(paymentService).processPayment(eq(PAYMENT_ID), anyString());
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();
        service.retryPendingPayments();
        service.retryPendingPayments();

        verify(paymentService, times(3)).processPayment(eq(PAYMENT_ID), anyString());
        assertEquals(3, service.getRetryCount(PAYMENT_ID));
        assertFalse(service.isRetryScheduled(PAYMENT_ID));
        verify(alertService, times(1)).alertException(
                eq("DunningService"), anyString(), any());
    }

    @Test
    void retryPendingPayments_alertAfterFailureDisabled_noAlert() {
        service.setAlertAfterFailure(false);
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));
        org.mockito.Mockito.doThrow(new IllegalStateException("gateway down"))
                .when(paymentService).processPayment(eq(PAYMENT_ID), anyString());
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();
        service.retryPendingPayments();
        service.retryPendingPayments();

        verify(alertService, never()).alertException(anyString(), anyString(), any());
        assertFalse(service.isRetryScheduled(PAYMENT_ID));
    }

    @Test
    void disabled_noRetriesAreScheduledOrExecuted() {
        service.setEnabled(false);
        lenient().when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());

        service.scheduleRetry(PAYMENT_ID);
        service.retryPendingPayments();

        assertFalse(service.isRetryScheduled(PAYMENT_ID));
        verify(paymentService, never()).processPayment(any(), anyString());
    }

    @Test
    void paymentNotFound_isDroppedFromQueueSilently() {
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.empty());
        service.scheduleRetry(PAYMENT_ID);

        service.retryPendingPayments();

        assertFalse(service.isRetryScheduled(PAYMENT_ID));
        verify(paymentService, never()).processPayment(any(), anyString());
    }

    @Test
    void retryScheduledOnOneReplica_isExecutableByAnother() {
        // Replica A schedules the retry; replica B (fresh service, same Redis)
        // drains it. Mirrors the cluster behaviour where the ShedLock winner
        // can be any replica.
        DunningService replicaA = service;
        when(paymentRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(paymentWithStatus(Payment.PaymentStatus.FAILED)));

        replicaA.scheduleRetry(PAYMENT_ID);

        DunningService replicaB = new DunningService(
                paymentRepository, paymentService, alertService, replicaRedisTemplate());
        replicaB.setEnabled(true);
        replicaB.setMaxRetries(3);
        replicaB.setRetryDelayMs(0);
        replicaB.setAlertAfterFailure(true);

        assertTrue(replicaB.isRetryScheduled(PAYMENT_ID));
        replicaB.retryPendingPayments();

        verify(paymentService).processPayment(eq(PAYMENT_ID), eq("dunning-" + PAYMENT_ID));
        assertFalse(replicaA.isRetryScheduled(PAYMENT_ID));
    }

    @SuppressWarnings("unchecked")
    private StringRedisTemplate replicaRedisTemplate() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        HashOperations<String, String, String> hashOps =
                (HashOperations<String, String, String>) (HashOperations<?, ?, ?>) hashStore;
        org.mockito.Mockito.doReturn(hashOps).when(redisTemplate).opsForHash();
        return redisTemplate;
    }
}
