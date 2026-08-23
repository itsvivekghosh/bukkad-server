package com.bhukkad.cart;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled entry point for the abandoned-cart recovery sweep.
 *
 * <p>Deliberately free of {@code @SchedulerLock}: the central integration
 * (ShedLock configuration) adds locking at a later stage. The interval is
 * {@code app.cart.recovery.interval-ms} (default 10 minutes).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartRecoveryScheduler {

    private final CartRecoveryProperties properties;
    private final CartRecoveryService cartRecoveryService;

    @Scheduled(fixedDelayString = "${app.cart.recovery.interval-ms:600000}")
    public void run() {
        if (!properties.isEnabled()) {
            log.debug("CART_RECOVERY_SCHEDULER_DISABLED");
            return;
        }
        cartRecoveryService.recoverAbandonedCarts();
    }
}
