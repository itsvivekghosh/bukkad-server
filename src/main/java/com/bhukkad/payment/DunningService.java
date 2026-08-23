package com.bhukkad.payment;

import com.bhukkad.entity.Payment;
import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed-payment dunning: schedules and executes payment retries for payments
 * stuck in PENDING or FAILED, and raises an alert once retries are exhausted.
 *
 * <p>Retry count and schedule are tracked in memory per payment id. The
 * {@link #retryPendingPayments()} scheduler drains the queued payment ids and
 * is guarded by {@code app.dunning.enabled} (default {@code false}). Deliberately
 * has no {@code @SchedulerLock} so it can only ever run once per node.
 */
@Slf4j
@Getter
@Setter
@Service
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "app.dunning")
public class DunningService {

    private boolean enabled = false;
    private int maxRetries = 3;
    private long retryDelayMs = 3600000;
    private boolean alertAfterFailure = true;

    private final PaymentRepository paymentRepository;
    @org.springframework.context.annotation.Lazy
    private final PaymentService paymentService;
    private final AlertService alertService;

    private ConcurrentHashMap<Long, Integer> retryCounts = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Long> nextRetryAtMillis = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Long, Boolean> scheduledRetries = new ConcurrentHashMap<>();

    /**
     * Registers a PENDING or FAILED payment for a retry attempt. The actual retry
     * is executed by the {@link #retryPendingPayments()} scheduler after the
     * configured retry delay has elapsed.
     */
    public void scheduleRetry(Long paymentId) {
        if (paymentId == null) {
            log.warn("Dunning scheduleRetry called with null payment id");
            return;
        }
        if (!enabled) {
            log.debug("Dunning disabled; skipping retry schedule | paymentId={}", paymentId);
            return;
        }
        scheduledRetries.put(paymentId, Boolean.TRUE);
        retryCounts.putIfAbsent(paymentId, 0);
        nextRetryAtMillis.put(paymentId, System.currentTimeMillis() + retryDelayMs);
        log.info("Payment scheduled for retry | paymentId={} | retryDelayMs={}", paymentId, retryDelayMs);
    }

    /**
     * Scheduled dunning pass: retries queued payments still in PENDING/FAILED and
     * raises an alert when a payment has exhausted its retries.
     */
    @Scheduled(fixedDelayString = "${app.dunning.scan-interval-ms:300000}")
    public void retryPendingPayments() {
        if (!enabled) {
            return;
        }
        List<Long> paymentIds = new ArrayList<>(scheduledRetries.keySet());
        for (Long paymentId : paymentIds) {
            try {
                retryPaymentIfDue(paymentId);
            } catch (Exception ex) {
                log.warn("Dunning pass failed for payment | paymentId={}", paymentId, ex);
            }
        }
    }

    private void retryPaymentIfDue(Long paymentId) {
        Long nextRetryAt = nextRetryAtMillis.get(paymentId);
        if (nextRetryAt != null && nextRetryAt > System.currentTimeMillis()) {
            return;
        }

        int retryCount = retryCounts.getOrDefault(paymentId, 0);
        if (retryCount >= maxRetries) {
            if (alertAfterFailure) {
                alertService.alertException("DunningService",
                        "Payment failed after max retries | paymentId=" + paymentId, null);
            }
            evict(paymentId);
            log.warn("Payment exhausted retries | paymentId={} | retryCount={}", paymentId, retryCount);
            return;
        }

        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Payment no longer exists; dropping from dunning | paymentId={}", paymentId);
            evict(paymentId);
            return;
        }
        if (payment.getStatus() != Payment.PaymentStatus.PENDING
                && payment.getStatus() != Payment.PaymentStatus.FAILED) {
            log.info("Payment no longer eligible for retry | paymentId={} | status={}", paymentId, payment.getStatus());
            evict(paymentId);
            return;
        }

        try {
            paymentService.processPayment(paymentId, "dunning-" + paymentId);
            evict(paymentId);
            log.info("Payment retry succeeded | paymentId={}", paymentId);
        } catch (Exception ex) {
            int newCount = retryCount + 1;
            retryCounts.put(paymentId, newCount);
            log.warn("Payment retry failed | paymentId={} | retryCount={}", paymentId, newCount, ex);
            if (newCount >= maxRetries) {
                if (alertAfterFailure) {
                    alertService.alertException("DunningService",
                            "Payment failed after max retries | paymentId=" + paymentId, ex);
                }
                evict(paymentId);
            } else {
                nextRetryAtMillis.put(paymentId, System.currentTimeMillis() + retryDelayMs);
            }
        }
    }

    private void evict(Long paymentId) {
        scheduledRetries.remove(paymentId);
        nextRetryAtMillis.remove(paymentId);
    }

    /** Current retry count for a payment (0 when never retried). */
    public int getRetryCount(Long paymentId) {
        return paymentId == null ? 0 : retryCounts.getOrDefault(paymentId, 0);
    }

    /** Whether a retry is currently queued for the payment. */
    public boolean isRetryScheduled(Long paymentId) {
        return paymentId != null && scheduledRetries.containsKey(paymentId);
    }

    /** When the next retry is due, or {@code null} when none is scheduled. */
    public LocalDateTime getNextRetryAt(Long paymentId) {
        Long millis = paymentId == null ? null : nextRetryAtMillis.get(paymentId);
        return millis == null ? null
                : Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}