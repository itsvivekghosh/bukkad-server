package com.bhukkad.payment.config;

import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Reaps orphaned {@link Payment} rows left in {@code IN_PROGRESS} when the
 * JVM crashes between the idempotency claim write and the PSP settle call.
 *
 * <p>The claim TTL is 24 h; anything older is safely abandoned and flipped
 * to {@code FAILED} so the customer/order flow can retry without manual
 * intervention.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.payment", name = "cleanup-enabled", havingValue = "true", matchIfMissing = true)
public class OrphanedPaymentCleanupScheduler {

    private static final long CLEANUP_WINDOW_HOURS = 24;

    private final PaymentRepository paymentRepository;

    @Scheduled(cron = "${app.payment.cleanup-cron:0 0 3 * * *}")
    public void cleanupOrphanedPayments() {
        Instant cutoff = Instant.now().minus(CLEANUP_WINDOW_HOURS, ChronoUnit.HOURS);
        List<Payment> orphans = paymentRepository.findByStatusAndCreatedAtBefore(
                Payment.STATUS_PENDING, cutoff.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime());
        orphans.addAll(paymentRepository.findByStatusAndCreatedAtBefore(
                Payment.STATUS_PROCESSING, cutoff.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()));

        if (orphans.isEmpty()) {
            log.debug("ORPHANED_PAYMENT_CLEANUP | count=0");
            return;
        }

        log.warn("ORPHANED_PAYMENT_CLEANUP | count={} | flipping to FAILED", orphans.size());
        orphans.forEach(p -> {
            try {
                p.setStatus(Payment.STATUS_FAILED);
                paymentRepository.save(p);
            } catch (Exception ex) {
                log.error("ORPHANED_PAYMENT_CLEANUP_FAILED | paymentId={} | error={}", p.getId(), ex.getMessage(), ex);
            }
        });
    }
}
