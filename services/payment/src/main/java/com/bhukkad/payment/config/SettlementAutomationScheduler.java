package com.bhukkad.payment.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.bhukkad.payment.domain.service.SettlementAutomationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Cron entrypoint for the automated settlement batch (G1, plan W2).
 *
 * <p>Gated on {@code app.settlement.auto-enabled} (default {@code false}):
 * per the cutover plan the scheduler stays dark until the settlement traffic
 * flip, and rollback means flag-off here plus a monolith scheduler restart —
 * never a database repair. The monolith's ShedLock was intentionally not
 * ported: {@link SettlementAutomationService} derives its pending amounts
 * from status-guarded atomic flips, making overlapping ticks duplicate-free.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.settlement", name = "auto-enabled", havingValue = "true")
public class SettlementAutomationScheduler {

    private final SettlementAutomationService settlementAutomationService;

    @Scheduled(cron = "${app.settlement.auto-settle-cron:0 0 2 * * *}")
    public void scheduledSettle() {
        try {
            settlementAutomationService.settleFor(LocalDate.now());
        } catch (Exception ex) {
            // A failed tick must never kill the scheduler thread; the next
            // tick retries because nothing was pinned for the date.
            log.error("SETTLEMENT_AUTOMATION_FAILED | date={} | error={}", LocalDate.now(), ex.toString(), ex);
        }
    }
}
