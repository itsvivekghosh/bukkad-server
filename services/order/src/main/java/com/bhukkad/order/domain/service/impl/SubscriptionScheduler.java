package com.bhukkad.order.domain.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.bhukkad.order.config.SubscriptionProperties;
import com.bhukkad.order.domain.entity.Subscription;

/**
 * Drives the recurring subscription materialization sweep.
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
