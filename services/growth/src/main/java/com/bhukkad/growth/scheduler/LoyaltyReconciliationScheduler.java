package com.bhukkad.growth.scheduler;

import com.bhukkad.growth.serviceImpl.LoyaltyReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly loyalty reconciliation job (ADR-005 / audit feature #4). The
 * scheduling pool is already sized at 12 ({@code SCHEDULER_POOL_SIZE});
 * {@code @SchedulerLock} keeps replicas serialised via the growth ShedLock
 * table (migration V10).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoyaltyReconciliationScheduler {

    private final LoyaltyReconciliationService reconciliationService;

    @Scheduled(cron = "${app.growth.loyalty.reconciliation-cron:0 30 3 * * *}")
    @SchedulerLock(name = "loyalty-reconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcileNightly() {
        try {
            var report = reconciliationService.reconcile();
            log.info("LOYALTY_RECONCILIATION_DONE correctedRows={} driftedCustomers={} totalDriftPoints={}",
                    report.correctedRows(), report.driftedCustomers(), report.totalDriftPoints());
        } catch (Exception ex) {
            // A failed tick must never kill the scheduler thread; the next
            // nightly tick (or manual run) retries because nothing is persisted here.
            log.error("LOYALTY_RECONCILIATION_FAILED | error={}", ex.toString(), ex);
        }
    }
}
