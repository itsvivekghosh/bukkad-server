package com.bhukkad.payment;

import com.bhukkad.entity.Order;
import com.bhukkad.entity.Payment;
import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.service.PaymentService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Failed-payment dunning: schedules and executes payment retries for payments
 * stuck in PENDING or FAILED, and raises an alert once retries are exhausted.
 *
 * <p>Retry state (count, next attempt, scheduled marker) is stored in Redis so
 * a retry scheduled on one replica is visible to and executed by exactly one
 * replica. The {@link #retryPendingPayments()} scheduler is guarded by a
 * ShedLock distributed lock, so only one node in a horizontally scaled fleet
 * drains the queue at a time — no duplicate money moves.</p>
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
    private final StringRedisTemplate stringRedisTemplate;

    static final String SCHEDULED_HASH = "bhukkad:dunning:scheduled";
    static final String RETRY_COUNT_HASH = "bhukkad:dunning:retry-count";
    static final String NEXT_RETRY_AT_HASH = "bhukkad:dunning:next-retry-at";

    /**
     * Local cache of scheduled payment ids (fast path for the getters). Redis is
     * the source of truth; this cache is refreshed on every scheduler pass.
     */
    private final ConcurrentHashMap<Long, Boolean> scheduledCache = new ConcurrentHashMap<>();

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
        scheduledCache.put(paymentId, true);
        try {
            stringRedisTemplate.opsForHash().put(SCHEDULED_HASH, String.valueOf(paymentId), "1");
            stringRedisTemplate.opsForHash().putIfAbsent(RETRY_COUNT_HASH, String.valueOf(paymentId), "0");
            stringRedisTemplate.opsForHash().put(NEXT_RETRY_AT_HASH, String.valueOf(paymentId),
                    String.valueOf(System.currentTimeMillis() + retryDelayMs));
        } catch (Exception ex) {
            log.warn("Dunning Redis write failed | paymentId={} | error={}", paymentId, ex.getMessage());
        }
        log.info("Payment scheduled for retry | paymentId={} | retryDelayMs={}", paymentId, retryDelayMs);
    }

    /**
     * Scheduled dunning pass: retries queued payments still in PENDING/FAILED and
     * raises an alert when a payment has exhausted its retries. ShedLock ensures
     * only one replica in the fleet executes this pass.
     */
    @Scheduled(fixedDelayString = "${app.dunning.scan-interval-ms:300000}")
    @SchedulerLock(name = "dunningRetryPass", lockAtMostFor = "PT15M", lockAtLeastFor = "PT30S")
    public void retryPendingPayments() {
        if (!enabled) {
            return;
        }
        Set<Long> paymentIds = scheduledPaymentIds();
        for (Long paymentId : paymentIds) {
            try {
                retryPaymentIfDue(paymentId);
            } catch (Exception ex) {
                log.warn("Dunning pass failed for payment | paymentId={}", paymentId, ex);
            }
        }
    }

    private Set<Long> scheduledPaymentIds() {
        try {
            Set<Object> keys = stringRedisTemplate.opsForHash().keys(SCHEDULED_HASH);
            scheduledCache.clear();
            Set<Long> ids = new java.util.HashSet<>();
            for (Object key : keys) {
                try {
                    Long id = Long.parseLong(String.valueOf(key));
                    ids.add(id);
                    scheduledCache.put(id, true);
                } catch (NumberFormatException ignored) {
                    log.warn("Dunning: ignoring non-numeric scheduled payment id | id={}", key);
                }
            }
            return ids;
        } catch (Exception ex) {
            log.warn("Dunning Redis read failed | error={}", ex.getMessage());
            return Set.copyOf(scheduledCache.keySet());
        }
    }

    private void retryPaymentIfDue(Long paymentId) {
        if (!isDue(paymentId)) {
            return;
        }

        int retryCount = getRetryCount(paymentId);
        if (retryCount >= maxRetries) {
            markExhausted(paymentId, retryCount);
            return;
        }

        Payment payment = eligiblePaymentOrNull(paymentId);
        if (payment == null) {
            return;
        }

        try {
            paymentService.processPayment(paymentId, "dunning-" + paymentId);
            evict(paymentId);
            log.info("Payment retry succeeded | paymentId={}", paymentId);
        } catch (Exception ex) {
            handleRetryFailure(paymentId, retryCount, ex);
        }
    }

    private boolean isDue(Long paymentId) {
        Long nextRetryAt = nextRetryAtMillis(paymentId);
        return nextRetryAt == null || nextRetryAt <= System.currentTimeMillis();
    }

    private void markExhausted(Long paymentId, int retryCount) {
        if (alertAfterFailure) {
            alertService.alertException("DunningService",
                    "Payment failed after max retries | paymentId=" + paymentId, null);
        }
        evict(paymentId);
        log.warn("Payment exhausted retries | paymentId={} | retryCount={}", paymentId, retryCount);
    }

    /**
     * Returns the payment if still eligible for retry, or {@code null} after
     * evicting it from the retry loop. Ineligible states: payment gone, status
     * no longer PENDING/FAILED, or the order was cancelled (compensation case).
     */
    private Payment eligiblePaymentOrNull(Long paymentId) {
        // Re-check the DB: the row may have been completed or cancelled since
        // the retry was scheduled.
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Payment no longer exists; dropping from dunning | paymentId={}", paymentId);
            evict(paymentId);
            return null;
        }
        if (payment.getStatus() != Payment.PaymentStatus.PENDING
                && payment.getStatus() != Payment.PaymentStatus.FAILED) {
            log.info("Payment no longer eligible for retry | paymentId={} | status={}", paymentId, payment.getStatus());
            evict(paymentId);
            return null;
        }
        if (payment.getOrder() != null
                && payment.getOrder().getStatus() == Order.OrderStatus.CANCELLED) {
            log.info("Payment dropped from dunning: order cancelled | paymentId={} | orderId={}",
                    paymentId, payment.getOrder().getId());
            evict(paymentId);
            return null;
        }
        return payment;
    }

    private void handleRetryFailure(Long paymentId, int retryCount, Exception ex) {
        int newCount = retryCount + 1;
        putRetryCount(paymentId, newCount);
        log.warn("Payment retry failed | paymentId={} | retryCount={}", paymentId, newCount, ex);
        if (newCount >= maxRetries) {
            if (alertAfterFailure) {
                alertService.alertException("DunningService",
                        "Payment failed after max retries | paymentId=" + paymentId, ex);
            }
            evict(paymentId);
        } else {
            putNextRetryAt(paymentId, System.currentTimeMillis() + retryDelayMs);
        }
    }

    private void evict(Long paymentId) {
        scheduledCache.remove(paymentId);
        try {
            stringRedisTemplate.opsForHash().delete(SCHEDULED_HASH, String.valueOf(paymentId));
            stringRedisTemplate.opsForHash().delete(NEXT_RETRY_AT_HASH, String.valueOf(paymentId));
        } catch (Exception ex) {
            log.warn("Dunning Redis evict failed | paymentId={} | error={}", paymentId, ex.getMessage());
        }
    }

    /** Current retry count for a payment (0 when never retried). */
    public int getRetryCount(Long paymentId) {
        if (paymentId == null) {
            return 0;
        }
        try {
            Object value = stringRedisTemplate.opsForHash().get(RETRY_COUNT_HASH, String.valueOf(paymentId));
            return value == null ? 0 : Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            log.warn("Dunning retry count read failed | paymentId={} | error={}", paymentId, ex.getMessage());
            return 0;
        }
    }

    /** Whether a retry is currently queued for the payment (Redis is the source of truth). */
    public boolean isRetryScheduled(Long paymentId) {
        if (paymentId == null) {
            return false;
        }
        try {
            boolean scheduled = Boolean.TRUE.equals(
                    stringRedisTemplate.opsForHash().hasKey(SCHEDULED_HASH, String.valueOf(paymentId)));
            if (scheduled) {
                scheduledCache.put(paymentId, true);
            } else {
                scheduledCache.remove(paymentId);
            }
            return scheduled;
        } catch (Exception ex) {
            log.warn("Dunning scheduled check failed | paymentId={} | error={}", paymentId, ex.getMessage());
            // Redis unavailable: fall back to the local cache.
            return scheduledCache.containsKey(paymentId);
        }
    }

    /** When the next retry is due, or {@code null} when none is scheduled. */
    public LocalDateTime getNextRetryAt(Long paymentId) {
        Long millis = nextRetryAtMillis(paymentId);
        return millis == null ? null
                : Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private Long nextRetryAtMillis(Long paymentId) {
        if (paymentId == null) {
            return null;
        }
        try {
            Object value = stringRedisTemplate.opsForHash().get(NEXT_RETRY_AT_HASH, String.valueOf(paymentId));
            return value == null ? null : Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            log.warn("Dunning next-retry-at read failed | paymentId={} | error={}", paymentId, ex.getMessage());
            return null;
        }
    }

    private void putRetryCount(Long paymentId, int count) {
        try {
            stringRedisTemplate.opsForHash().put(RETRY_COUNT_HASH, String.valueOf(paymentId), String.valueOf(count));
        } catch (Exception ex) {
            log.warn("Dunning retry count write failed | paymentId={} | error={}", paymentId, ex.getMessage());
        }
    }

    private void putNextRetryAt(Long paymentId, long millis) {
        try {
            stringRedisTemplate.opsForHash().put(NEXT_RETRY_AT_HASH, String.valueOf(paymentId), String.valueOf(millis));
        } catch (Exception ex) {
            log.warn("Dunning next-retry-at write failed | paymentId={} | error={}", paymentId, ex.getMessage());
        }
    }
}
