package com.bhukkad.order;

import com.bhukkad.config.SubscriptionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the recurring subscription materialization sweep. Every interval it
 * asks {@link SubscriptionService} to materialize all ACTIVE plans whose next
 * delivery date is due into real SCHEDULED orders.
 *
 * <p>Deliberately runs unlocked (no {@code @SchedulerLock}): materialization is
 * idempotent per plan (a PENDING/PLACED delivery row guards re-placement) so a
 * duplicate sweep in a multi-instance deployment cannot double-place an order.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private final SubscriptionProperties subscriptionProperties;
    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelayString = "${app.subscriptions.scheduler-interval-ms:3600000}")
    public void materialize() {
        if (!subscriptionProperties.isEnabled()) {
            log.debug("Subscription scheduler disabled; skipping materialization");
            return;
        }
        int processed = subscriptionService.materializeDue();
        if (processed > 0) {
            log.info("Materialized {} due subscription deliveries", processed);
        }
    }
}
